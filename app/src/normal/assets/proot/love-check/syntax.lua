-- Compile only: never invoke a project's chunks, including conf.lua.
local manifest = assert(io.open(assert(arg[1]), "rb"))
local filenames = manifest:read("*a")
manifest:close()
local failed = false
for filename in filenames:gmatch("([^%z]+)%z") do
    local chunk, err = loadfile(filename, "t")
    if not chunk then
        io.stderr:write(tostring(err), "\n")
        failed = true
    end
end
os.exit(failed and 1 or 0)
