package com.novel.nativelib


class NativeLib {



    companion object {
        // Used to load the 'nativelib' library on application startup.
        init {
            System.loadLibrary("nativelib")
        }
        @JvmStatic
        external fun sign(param: String): String?

        @JvmStatic
        external fun faerfa(): String
    }
}