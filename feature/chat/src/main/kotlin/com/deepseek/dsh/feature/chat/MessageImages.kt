package com.deepseek.dsh.feature.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.deepseek.dsh.core.protocol.AttachmentImageRef

/**
 * 消息里的图片缩略图（官方客户端把用户发的图渲染成圆角缩略图）。
 *
 * 图片字节按需通过 [ChatServices.loadImage] 拉取并缓存；宽度受限、保持比例，
 * 多图横向排列。
 */
@Composable
fun MessageImagesRow(
    images: List<AttachmentImageRef>,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.take(3).forEach { ref ->
            Thumbnail(ref)
        }
    }
}

@Composable
private fun Thumbnail(ref: AttachmentImageRef) {
    var bitmap by remember(ref.attachmentId) { mutableStateOf<ImageBitmap?>(null) }
    val services = LocalChatServices.current
    LaunchedEffect(ref.attachmentId) {
        val bytes = runCatching { services.loadImage(ref) }.getOrNull() ?: return@LaunchedEffect
        bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    // Фото над пузырём пользователя: скругление 16, как у плиток `.ph`.
    val c = com.deepseek.dsh.core.designsystem.Dsh.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(width = 132.dp, height = 132.dp)
            .clip(shape)
            .background(c.s2, shape)
            .border(1.dp, c.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = ref.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(132.dp),
            )
        }
    }
}
