package com.example.csed404_mobcomp_assignment_1_sensor_monitor

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.Executors

class DatasetZipExporter(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun validateAndExport(
        studentId: String,
        onValidationPassed: () -> Unit
    ) {
        executor.execute {
            val baseDir = context.getExternalFilesDir(null)
            val studentDir = File(baseDir, studentId)

            var validationMessage = "Dataset Status:\n"
            var hasWarnings = false

            for (activityClass in 0..6) {
                val classDir = File(studentDir, activityClass.toString())
                validationMessage += "Class $activityClass: "

                if (!classDir.exists()) {
                    validationMessage += "Missing\n"
                    hasWarnings = true
                    continue
                }

                var allGood = true
                var classMsg = ""
                var hasMissingFiles = false
                var hasPresentFiles = false
                
                listOf("linear.csv", "gravity.csv", "gyro.csv").forEach { fileName ->
                    val file = File(classDir, fileName)
                    if (!file.exists()) {
                        hasMissingFiles = true
                        allGood = false
                    } else {
                        hasPresentFiles = true
                        val sizeMb = file.length() / (1024f * 1024f)
                        val lineCount = file.readLines().size
                        classMsg += String.format(java.util.Locale.US, "$fileName (%.2f MB, %d lines), ", sizeMb, lineCount)
                        if (lineCount < 60000) {
                            allGood = false
                        }
                    }
                }

                if (!hasPresentFiles) {
                    validationMessage += "Missing\n"
                    hasWarnings = true
                    continue
                }

                if (allGood) {
                    validationMessage += "OK\n"
                } else {
                    if (hasMissingFiles) {
                        classMsg = "Partial (Missing: " + listOf("linear.csv", "gravity.csv", "gyro.csv").filter { !File(classDir, it).exists() }.joinToString(", ") + ") " + classMsg
                    }
                    validationMessage += classMsg.removeSuffix(", ") + "\n"
                    hasWarnings = true
                }
            }

            mainHandler.post {
                if (hasWarnings) {
                    AlertDialog.Builder(context)
                        .setTitle("Validation Warnings")
                        .setMessage(validationMessage)
                        .setPositiveButton("Export Anyway") { _, _ -> onValidationPassed() }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    AlertDialog.Builder(context)
                        .setTitle("Validation Successful")
                        .setMessage(validationMessage)
                        .setPositiveButton("Export") { _, _ -> onValidationPassed() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    fun exportToZip(studentId: String, destUri: Uri, onComplete: () -> Unit) {
        executor.execute {
            try {
                val baseDir = context.getExternalFilesDir(null)
                val studentDir = File(baseDir, studentId)

                var totalSize = 0L
                context.contentResolver.openOutputStream(destUri)?.let { outputStream ->
                    ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
                        if (studentDir.exists()) {
                            for (activityClass in 0..6) {
                                val classDir = File(studentDir, activityClass.toString())
                                if (classDir.exists()) {
                                    classDir.listFiles()?.forEach { file ->
                                        if (file.isFile) {
                                            val entryName = "${studentId}/${activityClass}/${file.name}"
                                            val zipEntry = ZipEntry(entryName)
                                            zos.putNextEntry(zipEntry)

                                            BufferedInputStream(FileInputStream(file)).use { bis ->
                                                val buffer = ByteArray(8192)
                                                var length: Int
                                                while (bis.read(buffer).also { length = it } > 0) {
                                                    zos.write(buffer, 0, length)
                                                    totalSize += length
                                                }
                                            }
                                            zos.closeEntry()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                val totalSizeMb = totalSize / (1024f * 1024f)
                
                mainHandler.post {
                    Toast.makeText(context, String.format(java.util.Locale.US, "Export complete: %.2f MB", totalSizeMb), Toast.LENGTH_SHORT).show()
                    onComplete()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    Toast.makeText(context, "Export failed: \${e.message}", Toast.LENGTH_LONG).show()
                    onComplete()
                }
            }
        }
    }
}
