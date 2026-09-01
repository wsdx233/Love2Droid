#include "common/runtime.h"

#include <jni.h>
#include <lua.h>
#include <lauxlib.h>

#include <deque>
#include <mutex>
#include <limits>
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
std::mutex debugMutex;
std::deque<std::string> debugCommands;
std::deque<std::string> debugLogs;

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

void executeDebugCode(lua_State *L, const std::string &code)
{
	appendDebugLog("> " + code);
	const int stackTop = lua_gettop(L);
	lua_pushcfunction(L, love::luax_traceback);
	const int tracebackIndex = stackTop + 1;
	if (luaL_loadbuffer(L, code.data(), code.size(), "=[Love2Droid Debug]") != 0)
	{
		const char *error = lua_tostring(L, -1);
		appendDebugLog(std::string("! ") + (error != nullptr ? error : "unknown load error"));
		lua_settop(L, stackTop);
		return;
	}

	const int status = lua_pcall(L, 0, LUA_MULTRET, tracebackIndex);
	if (status != 0)
	{
		const char *error = lua_tostring(L, -1);
		appendDebugLog(std::string("! ") + (error != nullptr ? error : "unknown runtime error"));
		lua_settop(L, stackTop);
		return;
	}

	const int resultTop = lua_gettop(L);
	if (resultTop > tracebackIndex)
	{
		std::string results = "= ";
		for (int index = tracebackIndex + 1; index <= resultTop; ++index)
		{
			if (index > tracebackIndex + 1)
				results += "\t";
			results += stringifyLuaValue(L, index);
		}
		appendDebugLog(results);
	}
	lua_settop(L, stackTop);
}
}

int love_android_debug_poll(lua_State *L)
{
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
		executeDebugCode(L, command);
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
}

extern "C" JNIEXPORT void JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeResetDebugState(
	JNIEnv *, jobject)
{
	std::lock_guard<std::mutex> lock(debugMutex);
	debugCommands.clear();
	debugLogs.clear();
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_top_wsdx233_love2droid_runtime_LoveGameActivity_nativeGetDebugLogs(
	JNIEnv *env, jobject)
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
	if (logs.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
		logs.resize(static_cast<size_t>(std::numeric_limits<jsize>::max()));
	jbyteArray result = env->NewByteArray(static_cast<jsize>(logs.size()));
	if (result != nullptr && !logs.empty())
		env->SetByteArrayRegion(result, 0, static_cast<jsize>(logs.size()), reinterpret_cast<const jbyte *>(logs.data()));
	return result;
}
