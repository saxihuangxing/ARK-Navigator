package space.taran.arknavigator.ui.resource

import android.content.Context
import androidx.annotation.StringRes
import space.taran.arknavigator.R
import space.taran.arklib.domain.kind.KindCode

class StringProvider(private val context: Context) {
    fun getString(@StringRes stringResId: Int): String {
        return context.getString(stringResId)
    }

    fun kindToString(kind: KindCode) = when (kind) {
        KindCode.IMAGE -> context.getString(R.string.kind_image)
        KindCode.VIDEO -> context.getString(R.string.kind_video)
        KindCode.DOCUMENT -> context.getString(R.string.kind_document)
        KindCode.LINK -> context.getString(R.string.kind_link)
        KindCode.ARCHIVE -> context.getString(R.string.kind_archive)
        KindCode.PLAINTEXT -> context.getString(R.string.kind_plain_text)
    }
}
