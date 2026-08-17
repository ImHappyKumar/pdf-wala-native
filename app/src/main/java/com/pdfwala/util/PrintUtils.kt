package com.pdfwala.util

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

/**
 * Print utility for PDF documents.
 *
 * Provides a simple way to print PDFs using Android's PrintManager.
 * Works with any PDF URI (content://, file://, etc.).
 */
object PrintUtils {

    /**
     * Print a PDF document using the system print dialog.
     *
     * @param context Application context
     * @param uri PDF document URI to print
     * @param documentName Optional document name shown in print dialog (defaults to "PDF Document")
     * @return true if print job was started successfully, false otherwise
     */
    fun printPdf(context: Context, uri: Uri, documentName: String = "PDF Document"): Boolean {
        return try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                ?: return false

            val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return false

            val printAdapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onLayoutCancelled()
                        return
                    }

                    val info = PrintDocumentInfo.Builder(documentName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .build()

                    callback.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onWriteCancelled()
                        return
                    }

                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destination.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback.onWriteFailed(e.message)
                    }
                }

                override fun onFinish() {
                    try {
                        fileDescriptor.close()
                    } catch (e: Exception) {
                        // Ignore close errors
                    }
                }
            }

            printManager.print(documentName, printAdapter, PrintAttributes.Builder().build())
            true
        } catch (e: Exception) {
            // Print failed silently — do not crash
            false
        }
    }
}
