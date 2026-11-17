@JavascriptInterface
    fun loadJsonFile(filename: String): String {
        val localFile = File(filesDir, filename)
        if (localFile.exists() && localFile.length() > 5) {
            return localFile.readText()
        }
        return try {
            assets.open("www/data/$filename").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "[]"
        }
    }
