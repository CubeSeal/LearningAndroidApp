package com.example.learning.repos
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class FileRepository(private val context: Context, private val directoryStr: String) {
    val directory = File(context.filesDir, directoryStr)

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    // Suspend function for writing
    suspend fun writeFile(filename: String, content: String) = withContext(Dispatchers.IO) {
        File(directory, filename).writeText(content)
    }

    suspend fun writeFileStream(filename: String, inputStream: InputStream) = withContext(Dispatchers.IO) {
        File(directory, filename).outputStream().use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }

    // Suspend function for reading
    suspend fun readFile(filename: String): String? = withContext(Dispatchers.IO) {
        try {
            File(directory, filename).readText()
        } catch (e: Exception) {
            null
        }
    }

    // Check if file exists (fast operation, but can still be suspended)
    suspend fun fileExists(filename: String): Boolean = withContext(Dispatchers.IO) {
        File(directory, filename).exists()
    }

    // Delete a file
    suspend fun deleteFile(filename: String): Boolean = withContext(Dispatchers.IO) {
        File(directory, filename).delete()
    }

    // List all files in directory
    suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        directory.listFiles()?.map { it.name } ?: emptyList()
    }

    suspend fun runShellCommand(vararg args: String): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val process = Runtime.getRuntime().exec(args)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            error.ifEmpty { output }
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }
    }

}
