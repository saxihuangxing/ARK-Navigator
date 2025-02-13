package space.taran.arknavigator.stub

import space.taran.arklib.ResourceId
import space.taran.arklib.domain.kind.ResourceKind
import space.taran.arklib.domain.index.ResourceMeta
import java.nio.file.attribute.FileTime
import java.util.Date

object TestData {
    fun metasById() = mapOf(
        R1 to ResourceMeta(
            R1,
            "Resource1",
            ".jpg",
            fileTime(),
            ResourceKind.Image()
        ),
        R2 to ResourceMeta(
            R2,
            "Resource2",
            ".jpg",
            fileTime(),
            ResourceKind.Image()
        ),
        R3 to ResourceMeta(
            R3,
            "Resource3",
            ".jpg",
            fileTime(),
            ResourceKind.Image()
        ),
        R4 to ResourceMeta(
            R4,
            "Resource4",
            ".odt",
            fileTime(),
            ResourceKind.Document()
        )
    )

    fun tagsById() = mapOf(
        R1 to setOf(TAG1, TAG2),
        R2 to setOf(TAG2),
        R3 to setOf(),
        R4 to setOf(TAG3, TAG4)
    )

    private fun fileTime() = FileTime.from(Date().toInstant())
}

val R1 = ResourceId(1L, 1L)
val R2 = ResourceId(2L, 2L)
val R3 = ResourceId(3L, 3L)
val R4 = ResourceId(4L, 4L)

const val TAG1 = "tag1"
const val TAG2 = "tag2"
const val TAG3 = "tag3"
const val TAG4 = "tag4"
