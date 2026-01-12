package horse.amazin.babymonitor.shared

import android.os.Bundle
import android.os.Parcel

fun unmarshallBundle(data: ByteArray): Bundle? {
    val parcel = Parcel.obtain()
    return try {
        parcel.unmarshall(data, 0, data.size)
        parcel.setDataPosition(0)
        parcel.readBundle()
    } finally {
        parcel.recycle()
    }

}

fun marshallBundle(bundle: Bundle): ByteArray {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(bundle)
        parcel.marshall()
    } finally {
        parcel.recycle()
    }
}