package com.example.learning.repos
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

interface FileSource {
    val directory: File
    suspend fun writeFile(filename: String, content: String): Unit
    suspend fun writeFileStream(filename: String, inputStream: InputStream): Long
    suspend fun readFile(filename: String): String?
    suspend fun fileExists(filename: String): Boolean
    suspend fun deleteFile(filename: String): Boolean
    suspend fun listFiles(): List<String>
    suspend fun runShellCommand(vararg args: String): String
}

class FileRepository(val context: Context, private val directoryStr: String): FileSource {
    override val directory = File(context.filesDir, directoryStr)

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    // Suspend function for writing
    override suspend fun writeFile(filename: String, content: String) = withContext(Dispatchers.IO) {
        File(directory, filename).writeText(content)
    }

    override suspend fun writeFileStream(filename: String, inputStream: InputStream) = withContext(Dispatchers.IO) {
        File(directory, filename).outputStream().use { outputStream ->
            inputStream.copyTo(outputStream)
        }
    }

    // Suspend function for reading
    override suspend fun readFile(filename: String): String? = withContext(Dispatchers.IO) {
        try {
            File(directory, filename).readText()
        } catch (e: Exception) {
            null
        }
    }

    // Check if file exists (fast operation, but can still be override suspended)
    override suspend fun fileExists(filename: String): Boolean = withContext(Dispatchers.IO) {
        File(directory, filename).exists()
    }

    // Delete a file
    override suspend fun deleteFile(filename: String): Boolean = withContext(Dispatchers.IO) {
        File(directory, filename).delete()
    }

    // List all files in directory
    override suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        directory.listFiles()?.map { it.name } ?: emptyList()
    }

    override suspend fun runShellCommand(vararg args: String): String = withContext(Dispatchers.IO) {
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

class FakeFileRepository(files: Map<String, String>, directoryString: String): FileSource {
    // I'm keeping track of what is created, already existing and deleted, so I'm not mutably deleting anything from
    // these maps. This makes it a lot easier to debug. That means it's the responsibility of the methods in this Fake
    // to actually reconstruct the as-at call currently existing files.
    var existedFiles = files.toMutableMap()
    var createdFiles = mutableMapOf<String, String>()
    var deletedFiles = mutableSetOf<String>()

    override val directory: File = File(directoryString)

    override suspend fun writeFile(filename: String, content: String): Unit {
        createdFiles[filename] = content
        existedFiles.remove(filename)
    }

    override suspend fun writeFileStream(filename: String, inputStream: InputStream): Long {
        val streamString = inputStream.read().toString()

        writeFile(filename, streamString)

        return streamString.length.toLong()
    }

    override suspend fun readFile(filename: String): String? {
        return createdFiles.getOrElse(filename) {
            existedFiles.getOrDefault(filename, null)
        }
    }

    override suspend fun fileExists(filename: String): Boolean {
        return filename in createdFiles.keys || filename in existedFiles.keys
    }

    override suspend fun deleteFile(filename: String): Boolean {
        deletedFiles.add(filename)

        return true
    }

    override suspend fun listFiles(): List<String> {
        return (existedFiles.keys + createdFiles.keys - deletedFiles).toList()
    }

    override suspend fun runShellCommand(vararg args: String): String = "Not a real shell."
}
