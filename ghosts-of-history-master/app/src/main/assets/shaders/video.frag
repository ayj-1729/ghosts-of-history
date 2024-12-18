#extension GL_OES_EGL_image_external : require

precision mediump float;
varying vec2 v_TexCoord;
uniform samplerExternalOES sTexture;

uniform vec3 keyColor;
uniform float threshold;
const float smoothness = 0.08;
const float spill = 0.1;

uniform float time;
uniform int is_background;

const float transparency = 0.8; // Transparency of the final image
const float backgroundTransparency = 0.6; // Transparency of background

vec2 RGBtoUV(vec3 rgb) {
  return vec2(
    rgb.r * -0.169 + rgb.g * -0.331 + rgb.b *  0.5    + 0.5,
    rgb.r *  0.5   + rgb.g * -0.419 + rgb.b * -0.081  + 0.5
  );
}


vec4 ProcessChromaKey(vec4 rgba) {
  float chromaDist = distance(RGBtoUV(rgba.rgb), RGBtoUV(keyColor));

  float baseMask = chromaDist - threshold;
  float fullMask = pow(clamp(baseMask / smoothness, 0., 1.), 1.5);
  rgba.a = fullMask;

  float spillVal = pow(clamp(baseMask / spill, 0., 1.), 1.5);
  float desat = clamp(rgba.r * 0.2126 + rgba.g * 0.7152 + rgba.b * 0.0722, 0., 1.);
  rgba.rgb = mix(vec3(desat, desat, desat), rgba.rgb, spillVal);

  return rgba;
}

void main() {
    float xCoord = v_TexCoord.x;
    float yCoord = v_TexCoord.y;

    if (is_background == 1) {
        yCoord = yCoord * 1.5 - 0.6;  // display the background video at the bottom
    }

    vec4 color = texture2D(sTexture, vec2(xCoord, yCoord));

    if (length(color.rgb) == 0.0) {
        // This is a completely black pixel - probably the video hasn't loaded yet.
        // We just make it fully transparent in this case.
        gl_FragColor = vec4(color.rgb, 0.0);
        return;
    }
    float coefForAppearing = min(1.0, time / 4.0);

    if (is_background == 1) {
        float finalAlpha = length(color.rgb) / length(vec3(1., 1., 1.)) * backgroundTransparency * coefForAppearing;
        finalAlpha *= 16.0 * xCoord * (1.0 - xCoord) * yCoord * (1.0 - yCoord);
        gl_FragColor = vec4(color.rgb, finalAlpha);
    }
    else {
        vec4 greenScreenRgba = ProcessChromaKey(color);
        float alpha = greenScreenRgba.a;
        float arg = yCoord * 200.0 - time * 10.0;
        float coefForStripes = (sin(arg) + cos(2.0 * arg)  - sin(0.4 * arg) + 7.0) / 9.0;
        float coefForSuddenBlink = 1.0;
        int blinkIndex = int((time / 5.0 - floor(time / 5.0)) * 60.0);
        if (blinkIndex == 1 || blinkIndex == 7 || blinkIndex == 36 || blinkIndex == 45) {
            coefForSuddenBlink = 1.3;
        }

        float finalAlpha = alpha * transparency * coefForAppearing * coefForStripes * coefForSuddenBlink;
        finalAlpha = min(1.0, finalAlpha);

        gl_FragColor = vec4(greenScreenRgba.rgb, finalAlpha);
    }
}
