function love.load()
    local font = love.graphics.newFont("assets/fonts/fusion-pixel-12px-monospaced-zh_hans.otf", 24)
    font:setFilter("nearest", "nearest")
    love.graphics.setFont(font)
end

function love.draw()
    love.graphics.print("Hello from Love2Droid", 32, 32)
end
