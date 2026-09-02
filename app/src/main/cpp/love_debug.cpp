#include "common/runtime.h"

#include <jni.h>
#include <lua.h>
#include <lauxlib.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <limits>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace
{
constexpr size_t MAX_DEBUG_COMMAND_LENGTH = 64 * 1024;
constexpr size_t MAX_DEBUG_COMMANDS = 64;
constexpr size_t MAX_DEBUG_LOG_ENTRIES = 1000;
constexpr size_t MAX_DEBUG_LOG_LENGTH = 8 * 1024;
constexpr size_t MAX_DEBUG_LOG_OUTPUT = 256 * 1024;
constexpr size_t MAX_BREAKPOINT_FILE_LENGTH = 1024;
constexpr size_t MAX_BREAKPOINT_CONDITION_LENGTH = 2048;

enum StepMode
{
	STEP_NONE = 0,
	STEP_OVER = 1,
	STEP_INTO = 2,
	STEP_OUT = 3,
};

struct Breakpoint
{
	std::string file;
	int line = 0;
	std::string condition;
};

std::mutex debugMutex;
std::condition_variable debugCondition;
std::deque<std::string> debugCommands;
std::deque<std::string> debugLogs;
std::vector<Breakpoint> breakpoints;
std::string debugState = "paused=0\nreason=\nsource=\nline=0\nstack=";
lua_State *debugLuaState = nullptr;
bool debugHookInstalled = false;
bool debugPaused = false;
bool debugPauseRequested = false;
bool debugResumeRequested = false;
StepMode debugStepMode = STEP_NONE;
std::atomic<int> debugDepth{0};
int debugStepTargetDepth = 0;
thread_local bool debugExecuting = false;
thread_local bool debugInstructionLimited = false;

void appendDebugLog(const std::string &line)
{
	std::string stored = line;
	if (stored.size() > MAX_DEBUG_LOG_LENGTH)
		stored.resize(MAX_DEBUG_LOG_LENGTH);
	std::lock_guard<std::mutex> lock(debugMutex);
	if (debugLogs.size() >= MAX_DEBUG_LOG_ENTRIES)
		debugLogs.pop_front();
	debugLogs.push_back(std::move(stored));
}

std::string stringifyLuaValue(lua_State *L, int index)
{
	const int absoluteIndex = index > 0 ? index : lua_gettop(L) + index + 1;
	const int stackTop = lua_gettop(L);
	lua_getglobal(L, "tostring");
	lua_pushvalue(L, absoluteIndex);
	if (lua_pcall(L, 1, 1, 0) != 0)
	{
		const char *error = lua_tostring(L, -1);
		std::string result = "<tostring failed: ";
		result += error != nullptr ? error : "unknown error";
		result += ">";
		lua_settop(L, stackTop);
		return result;
	}
	const char *value = lua_tostring(L, -1);
	std::string result = value != nullptr ? value : "<non-string>";
	lua_settop(L, stackTop);
	return result;
}

std::string singleLine(std::string value)
{
	for (char &character : value)
	{
		if (character == '\n' || character == '\r' || character == '|')
			character = ' ';
	}
	return value;
}

std::string sourceName(const char *source)
{
	std::string result = source != nullptr ? source : "?";
	if (!result.empty() && result.front() == '@')
		result.erase(result.begin());
	return singleLine(std::move(result));
}

bool matchesSource(const std::string &current, const std::string &requested)
{
	if (current == requested)
		return true;
	if (current.size() > requested.size()
		&& current.compare(current.size() - requested.size(), requested.size(), requested) == 0
		&& current[current.size() - requested.size() - 1] == '/')
		return true;
	return false;
}

bool evaluateCondition(lua_State *L, const std::string &condition, bool &conditionError)
{
	conditionError = false;
	if (condition.empty())
		return true;
	const int stackTop = lua_gettop(L);
	lua_pushcfunction(L, love::luax_traceback);
	const int tracebackIndex = stackTop + 1;
	const std::string chunk = "return (" + condition + ")";
	if (luaL_loadbuffer(L, chunk.data(), chunk.size(), "=[Love2Droid Breakpoint]") != 0)
	{
		conditionError = true;
		const char *error = lua_tostring(L, -1);
		appendDebugLog(std::string("! Breakpoint condition: ") + (error != nullptr ? error : "load error"));
		lua_settop(L, stackTop);
		return true;
	}
	if (lua_pcall(L, 0, 1, tracebackIndex) != 0)
	{
		conditionError = true;
		const char *error = lua_tostring(L, -1);
		appendDebugLog(std::string("! Breakpoint condition: ") + (error != nullptr ? error : "runtime error"));
		lua_settop(L, stackTop);
		return true;
	}
	const bool result = lua_toboolean(L, -1) != 0;
	lua_settop(L, stackTop);
	return result;
}

bool breakpointMatches(lua_State *L, lua_Debug *ar, std::string &reason)
{
	const std::string current = sourceName(ar->source);
	std::vector<Breakpoint> candidates;
	{
		std::lock_guard<std::mutex> lock(debugMutex);
		candidates = breakpoints;
	}
	for (const Breakpoint &breakpoint : candidates)
	{
		if (breakpoint.line != ar->currentline || !matchesSource(current, breakpoint.file))
			continue;
        bool conditionError = false;
        const bool wasExecuting = debugExecuting;
        debugExecuting = true;
        const bool conditionMatched = evaluateCondition(L, breakpoint.condition, conditionError);
        debugExecuting = wasExecuting;
        if (conditionMatched)
		{
			reason = conditionError ? "断点条件错误" : "断点命中";
			return true;
		}
	}
	return false;
}

void captureDebugState(lua_State *L, lua_Debug *current, const std::string &reason)
{
	std::string stack;
	lua_Debug frame;
	for (int level = 0; lua_getstack(L, level, &frame); ++level)
	{
		if (!lua_getinfo(L, "Sln", &frame))
			continue;
		if (!stack.empty())
			stack += "|";
		stack += sourceName(frame.source);
		stack += ":";
		stack += std::to_string(frame.currentline);
		if (frame.name != nullptr && frame.name[0] != '\0')
		{
			stack += " ";
			stack += singleLine(frame.name);
		}
		if (level >= 15)
			break;
	}
	std::string snapshot = "paused=1\nreason=" + reason + "\nsource="
		+ sourceName(current->source) + "\nline=" + std::to_string(current->currentline)
		+ "\nstack=" + stack;
    std::lock_guard<std::mutex> lock(debugMutex);
    debugState = std::move(snapshot);
}
void executeDebugCode(lua_State *L, const std::string &code);
void debugHook(lua_State *L, lua_Debug *ar)
{
    if (debugExecuting)
    {
        if (debugInstructionLimited && ar->event == LUA_HOOKCOUNT)
            luaL_error(L, "debug code exceeded instruction budget");
        return;
    }
	if (ar->event == LUA_HOOKCALL)
	{
		++debugDepth;
		return;
	}
	if (ar->event == LUA_HOOKRET)
	{
		debugDepth = std::max(0, debugDepth - 1);
		return;
	}
	if (ar->event != LUA_HOOKLINE || !lua_getinfo(L, "Sln", ar))
		return;

	std::string reason;
	bool shouldPause = false;
	{
		std::lock_guard<std::mutex> lock(debugMutex);
		shouldPause = debugPauseRequested;
		if (shouldPause)
			debugPauseRequested = false;
		if (!shouldPause && debugStepMode == STEP_INTO)
			shouldPause = true;
		if (!shouldPause && debugStepMode == STEP_OVER && debugDepth <= debugStepTargetDepth)
			shouldPause = true;
		if (!shouldPause && debugStepMode == STEP_OUT && debugDepth < debugStepTargetDepth)
			shouldPause = true;
	}
	if (!shouldPause)
		shouldPause = breakpointMatches(L, ar, reason);
	if (!shouldPause)
		return;
	if (reason.empty())
		reason = "手动暂停";

	captureDebugState(L, ar, reason);
	{
		std::lock_guard<std::mutex> lock(debugMutex);
		debugPaused = true;
		debugStepMode = STEP_NONE;
		debugResumeRequested = false;
	}
	debugCondition.notify_all();

	for (;;)
	{
		std::string command;
		{
			std::unique_lock<std::mutex> lock(debugMutex);
			if (debugResumeRequested)
			{
				debugResumeRequested = false;
				debugPaused = false;
				debugState = "paused=0\nreason=\nsource=\nline=0\nstack=";
				break;
			}
			if (!debugCommands.empty())
			{
				command = std::move(debugCommands.front());
				debugCommands.pop_front();
			}
			else
			{
				debugCondition.wait_for(lock, std::chrono::milliseconds(50));
				continue;
			}
		}
        debugExecuting = true;
        // REPL remains usable while paused; hook callbacks are ignored for this call.
        executeDebugCode(L, command);
        debugExecuting = false;
	}
}

void ensureDebugHook(lua_State *L)
{
	if (debugLuaState != L)
	{
		debugLuaState = L;
		debugHookInstalled = false;
		debugDepth = 0;
	}
	bool needed;
	{
		std::lock_guard<std::mutex> lock(debugMutex);
		needed = !breakpoints.empty() || debugPauseRequested || debugPaused || debugStepMode != STEP_NONE;
	}
	if (needed && !debugHookInstalled)
	{
		lua_sethook(L, debugHook, LUA_MASKCALL | LUA_MASKRET | LUA_MASKLINE, 0);
		debugHookInstalled = true;
	}
	else if (!needed && debugHookInstalled)
	{
		lua_sethook(L, nullptr, 0, 0);
		debugHookInstalled = false;
	}
}

void executeDebugCode(lua_State *L, const std::string &code)
{
    appendDebugLog("> " + code);
    const int previousMask = lua_gethookmask(L);
    lua_Hook previousHook = lua_gethook(L);
    const int previousCount = lua_gethookcount(L);
    const bool previousLimited = debugInstructionLimited;
    debugInstructionLimited = true;
    lua_sethook(L, debugHook, previousMask | LUA_MASKCOUNT, 100000);

    const int stackTop = lua_gettop(L);
    lua_pushcfunction(L, love::luax_traceback);
    const int tracebackIndex = stackTop + 1;
    if (luaL_loadbuffer(L, code.data(), code.size(), "=[Love2Droid Debug]") != 0)
    {
        const char *error = lua_tostring(L, -1);
        appendDebugLog(std::string("! ") + (error != nullptr ? error : "unknown load error"));
    }
    else
    {
        const int status = lua_pcall(L, 0, LUA_MULTRET, tracebackIndex);
        if (status != 0)
        {
            const char *error = lua_tostring(L, -1);
            appendDebugLog(std::string("! ") + (error != nullptr ? error : "unknown runtime error"));
        }
        else if (lua_gettop(L) > tracebackIndex)
        {
            std::string results = "= ";
            for (int index = tracebackIndex + 1; index <= lua_gettop(L); ++index)
            {
                if (index > tracebackIndex + 1)
                    results += "\t";
                results += stringifyLuaValue(L, index);
            }
            appendDebugLog(results);
        }
    }
    lua_settop(L, stackTop);
    debugInstructionLimited = previousLimited;
    lua_sethook(L, previousHook, previousMask, previousCount);
}

std::string jniString(JNIEnv *env, jstring value, size_t maxLength)
{
	if (value == nullptr)
		return {};
	const char *chars = env->GetStringUTFChars(value, nullptr);
	if (chars == nullptr)
		return {};
	std::string result(chars);
	env->ReleaseStringUTFChars(value, chars);
	if (result.size() > maxLength)
		result.resize(maxLength);
	return result;
}

jbyteArray bytesToJava(JNIEnv *env, const std::string &value)
{
	const size_t maxSize = static_cast<size_t>(std::numeric_limits<jsize>::max());
	const size_t size = std::min(value.size(), maxSize);
	jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
	if (result != nullptr && size > 0)
		env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(value.data()));
	return result;
}
}

int love_android_debug_poll(lua_State *L)
{
	ensureDebugHook(L);
	std::string command;
	{
		std::lock_guard<std::mutex> lock(debugMutex);
		if (!debugCommands.empty())
		{
			command = std::move(debugCommands.front());
			debugCommands.pop_front();
		}
	}
	if (!command.empty())
	{
		debugExecuting = true;
		executeDebugCode(L, command);
		debugExecuting = false;
	}
	return 0;
}

void love_android_debug_log(const std::string &line)
{
	appendDebugLog(line);
}

extern "C" JNIEXPORT void JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeQueueDebugCode(
	JNIEnv *env, jobject, jbyteArray code)
{
	if (code == nullptr)
		return;
	const jsize length = env->GetArrayLength(code);
	if (length <= 0)
		return;
	if (static_cast<size_t>(length) > MAX_DEBUG_COMMAND_LENGTH)
	{
		appendDebugLog("! Debug code exceeds 64 KiB and was not executed");
		return;
	}
	std::string command(static_cast<size_t>(length), '\0');
	env->GetByteArrayRegion(code, 0, length, reinterpret_cast<jbyte *>(command.data()));
	bool queueFull = false;
	{
		std::lock_guard<std::mutex> lock(debugMutex);
		if (debugCommands.size() >= MAX_DEBUG_COMMANDS)
			queueFull = true;
		else
			debugCommands.push_back(std::move(command));
	}
	if (queueFull)
		appendDebugLog("! Debug command queue is full; command was not queued");
	debugCondition.notify_all();
}

extern "C" JNIEXPORT void JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeResetDebugState(
	JNIEnv *, jobject)
{
	std::lock_guard<std::mutex> lock(debugMutex);
	debugCommands.clear();
	debugLogs.clear();
	breakpoints.clear();
	debugState = "paused=0\nreason=\nsource=\nline=0\nstack=";
	debugPaused = false;
	debugPauseRequested = false;
	debugResumeRequested = true;
	debugStepMode = STEP_NONE;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeGetDebugLogs(JNIEnv *env, jobject)
{
	std::vector<std::string> selected;
	size_t total = 0;
	{
		std::lock_guard<std::mutex> lock(debugMutex);
		for (auto it = debugLogs.rbegin(); it != debugLogs.rend() && total < MAX_DEBUG_LOG_OUTPUT; ++it)
		{
			std::string line = *it;
			const size_t remaining = MAX_DEBUG_LOG_OUTPUT - total;
			if (line.size() > remaining)
				line.resize(remaining);
			total += line.size();
			selected.push_back(std::move(line));
		}
	}

	std::string logs;
	for (auto it = selected.rbegin(); it != selected.rend(); ++it)
	{
		if (!logs.empty())
			logs += "\n";
		logs += *it;
	}
	return bytesToJava(env, logs);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeGetDebugState(
	JNIEnv *env, jobject)
{
	std::lock_guard<std::mutex> lock(debugMutex);
	return bytesToJava(env, debugState);
}

extern "C" JNIEXPORT void JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeClearDebugLogs(
	JNIEnv *, jobject)
{
	std::lock_guard<std::mutex> lock(debugMutex);
	debugLogs.clear();
}

extern "C" JNIEXPORT void JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeSetBreakpoint(
	JNIEnv *env, jobject, jstring file, jint line, jstring condition)
{
	std::string fileName = jniString(env, file, MAX_BREAKPOINT_FILE_LENGTH);
	std::string conditionText = jniString(env, condition, MAX_BREAKPOINT_CONDITION_LENGTH);
	if (fileName.empty() || line <= 0)
		return;
	std::lock_guard<std::mutex> lock(debugMutex);
	for (Breakpoint &breakpoint : breakpoints)
	{
		if (breakpoint.file == fileName && breakpoint.line == line)
		{
			breakpoint.condition = std::move(conditionText);
			return;
		}
	}
	breakpoints.push_back({std::move(fileName), line, std::move(conditionText)});
}


extern "C" JNIEXPORT void JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeResumeDebug(
	JNIEnv *, jobject)
{
	std::lock_guard<std::mutex> lock(debugMutex);
	debugResumeRequested = true;
	debugPauseRequested = false;
	debugStepMode = STEP_NONE;
	debugCondition.notify_all();
}

extern "C" JNIEXPORT void JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeStepDebug(
	JNIEnv *, jobject, jint mode)
{
	std::lock_guard<std::mutex> lock(debugMutex);
	debugStepMode = mode == 1 ? STEP_INTO : mode == 2 ? STEP_OUT : STEP_OVER;
	debugStepTargetDepth = debugDepth;
	debugResumeRequested = true;
	debugPauseRequested = false;
	debugCondition.notify_all();
}

extern "C" JNIEXPORT void JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativePauseDebug(
	JNIEnv *, jobject)
{
	std::lock_guard<std::mutex> lock(debugMutex);
	debugPauseRequested = true;
	debugCondition.notify_all();
}
