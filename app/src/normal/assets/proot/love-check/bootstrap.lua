-- Loaded before the game's conf.lua, outside its virtual filesystem.
local check = { phase = "config", frames = 0 }
local exit, open, traceback = os.exit, io.open, debug.traceback
local reportPath = assert(os.getenv("LOVE_CHECK_REPORT"))
local frameLimit = assert(tonumber(os.getenv("LOVE_CHECK_FRAMES")))
io.stdout:setvbuf("no")
io.stderr:setvbuf("no")

local function quote(value)
    return '"' .. tostring(value):gsub('[%z\1-\31\\"]', function(c)
        if c == '"' then return '\\"' end
        if c == '\\' then return '\\\\' end
        return string.format("\\u%04x", string.byte(c))
    end) .. '"'
end

local function finish(status, message, code)
    local fields = {
        '"status":' .. quote(status),
        '"phase":' .. quote(check.phase),
        '"frames":' .. tostring(check.frames),
        '"engine":' .. quote(love._version),
    }
    if check.renderer then fields[#fields + 1] = '"renderer":' .. quote(check.renderer) end
    if message then fields[#fields + 1] = '"error":' .. quote(message:sub(1, 65536)) end
    local output, err = open(reportPath, "wb")
    if not output then
        io.stderr:write("Cannot write checker result: ", tostring(err), "\n")
        exit(2)
    end
    local written, writeError = output:write("{" .. table.concat(fields, ",") .. "}\n")
    local closed, closeError = output:close()
    if not written or not closed then
        io.stderr:write("Cannot finish checker result: ", tostring(writeError or closeError), "\n")
        exit(2)
    end
    exit(code)
end

function check.fail(message)
    local detail = traceback(tostring(message), 2)
    io.stderr:write(detail, "\n")
    finish("error", detail, 1)
end

local function pack(...)
    return { n = select("#", ...), ... }
end

function check.call(phase, fn, ...)
    check.phase = phase
    local wasLoading = check.loading
    if phase == "love.load" then check.loading = true end
    local arguments = pack(...)
    local result = pack(xpcall(function()
        return fn(unpack(arguments, 1, arguments.n))
    end, check.fail))
    if not result[1] then check.fail(result[2]) end
    check.loading = wasLoading
    return unpack(result, 2, result.n)
end

function check.loadSource(path, name)
    return check.call(name, function()
        local source, err = love.filesystem.read(path)
        if not source then error(err) end
        local chunk, syntaxError = loadstring(source, "@" .. name)
        if not chunk then error(syntaxError) end
        return chunk()
    end)
end

function check.configure(configure, config)
    if configure then check.call("love.conf", configure, config) end
    check.phase = "config"
    if not love.isVersionCompatible(tostring(config.version)) then
        check.fail("Game requests LÖVE " .. tostring(config.version) .. "; this checker runs 11.5")
    end
    -- Prevent an incompatible-version dialog from hanging in the virtual display.
    -- Keep the game's window, graphics and audio settings unchanged.
    love.errorhandler = check.fail
    love.errhand = check.fail
end

function check.start()
    check.phase = "graphics"
    if love._version ~= "11.5" then check.fail("Expected LÖVE 11.5, got " .. tostring(love._version)) end
    if not love.graphics then check.fail("Game disables graphics; a rendered-frame smoke check cannot run") end
    local name, version, vendor, device = love.graphics.getRendererInfo()
    check.renderer = table.concat({ name, version, vendor, device }, " / ")
    if not check.renderer:lower():find("llvmpipe", 1, true) then
        check.fail("Expected Mesa llvmpipe software rendering, got " .. check.renderer)
    end
    local present = love.graphics.present
    love.graphics.present = function(...)
        local previousPhase = check.phase
        check.phase = "present"
        if not love.graphics.isActive() then check.fail("Cannot count a frame without an active graphics context") end
        present(...)
        if check.running and not check.loading then
            check.frames = check.frames + 1
            if check.frames >= frameLimit then finish("passed", nil, 0) end
        end
        check.phase = previousPhase
    end
end

function check.wrapRun()
    love.errorhandler = check.fail
    love.errhand = check.fail
    for _, name in ipairs({ "load", "update", "draw" }) do
        local callback = love[name]
        if type(callback) == "function" then
            love[name] = function(...) return check.call("love." .. name, callback, ...) end
        end
    end
    local run = love.run
    love.run = function(...)
        check.running = true
        local loop = check.call("love.run", run, ...)
        if type(loop) ~= "function" then
            finish("incomplete", "Game exited before the requested number of rendered frames", 3)
        end
        return function(...)
            local code = check.call("frame", loop, ...)
            if code ~= nil then
                finish("incomplete", "Game exited before the requested number of rendered frames (exit " .. tostring(code) .. ")", 3)
            end
        end
    end
end

love.errorhandler = check.fail
love.errhand = check.fail
return check
