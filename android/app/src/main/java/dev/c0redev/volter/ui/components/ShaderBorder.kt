package dev.c0redev.volter.ui.components

import android.graphics.RuntimeShader
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush

// бегущий зелёный курсор по периметру рамки, stable-индикатор активного соединения
private const val RUNNING_BORDER_SRC = """
uniform float2 u_res;
uniform float  u_time;

const half3 u_color = half3(0.435, 0.682, 0.435);

half4 main(float2 frag) {
    float px = 1.5;
    float bx = px / u_res.x;
    float by = px / u_res.y;
    float2 uv = frag / u_res;

    float w = u_res.x;
    float h = u_res.y;
    float perim = 2.0 * (w + h);
    float x = frag.x;
    float y = frag.y;

    bool onBorder = uv.x < bx || uv.x > 1.0 - bx ||
                    uv.y < by || uv.y > 1.0 - by;

    float t = 0.0;
    if (onBorder) {
        if      (y < px)     t = x / perim;
        else if (x > w - px) t = (w + y) / perim;
        else if (y > h - px) t = (w + h + (w - x)) / perim;
        else                 t = (2.0 * w + h + (h - y)) / perim;
    }

    float cursor = mod(u_time * 0.5, 1.0);
    float dist   = mod(cursor - t + 1.0, 1.0);
    float tail   = (1.0 - smoothstep(0.0, 0.28, dist)) * step(dist, 0.28);
    float head   = 1.0 - smoothstep(0.0, 0.02, dist);
    float bright = max(tail, head);

    float hp = cursor * perim;
    float2 headPos;
    if      (hp < w)         headPos = float2(hp,       0.75);
    else if (hp < w + h)     headPos = float2(w - 0.75, hp - w);
    else if (hp < 2.0*w + h) headPos = float2(w - (hp - w - h), h - 0.75);
    else                     headPos = float2(0.75,     h - (hp - 2.0*w - h));

    float bd  = distance(float2(x, y), headPos);
    float blm = exp(-(bd * bd) / 30.0) * 0.55;

    if (!onBorder && blm < 0.008) return half4(0.0);
    if (!onBorder)                return half4(u_color * blm, blm);

    float alpha = 0.18 + bright * 0.78;
    return half4(u_color * alpha, alpha);
}
"""

// рисует анимированную рамку поверх контента, пока active == true
fun Modifier.runningBorder(active: Boolean): Modifier = composed {
    if (!active) return@composed this

    val shader = remember { RuntimeShader(RUNNING_BORDER_SRC) }
    val brush = remember { ShaderBrush(shader) }
    val transition = rememberInfiniteTransition(label = "runningBorder")
    // время в секундах, период кратен 2с (циклу курсора), шов незаметен
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 120f,
        animationSpec = infiniteRepeatable(tween(120_000, easing = LinearEasing)),
        label = "time",
    )

    drawWithCache {
        shader.setFloatUniform("u_res", size.width, size.height)
        onDrawWithContent {
            drawContent()
            shader.setFloatUniform("u_time", time)
            drawRect(brush)
        }
    }
}
