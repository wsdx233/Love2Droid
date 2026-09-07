local image, font, shader, audio

function love.load()
    -- Exercise decoding a real project asset, not a stand-in graphics API.
    image = love.graphics.newImage("pixel.png")
    font = love.graphics.newFont(14)
    shader = love.graphics.newShader([[
        vec4 effect(vec4 color, Image texture, vec2 uv, vec2 screen) {
            return Texel(texture, uv) * color;
        }
    ]])
    local samples = love.sound.newSoundData(2205, 44100, 16, 1)
    audio = love.audio.newSource(samples, "static")
    audio:play()
    assert(love.filesystem.write("doctor-save.txt", "isolated test save"))
    assert(love.filesystem.read("doctor-save.txt") == "isolated test save")
end

function love.update(dt)
    assert(type(dt) == "number")
end

function love.draw()
    love.graphics.setShader(shader)
    love.graphics.draw(image, 20, 20, 0, 16, 16)
    love.graphics.setShader()
    love.graphics.circle("fill", 70, 30, 10)
    love.graphics.setFont(font)
    love.graphics.print("LÖVE headless: image, font, shader, audio", 20, 60)
end
