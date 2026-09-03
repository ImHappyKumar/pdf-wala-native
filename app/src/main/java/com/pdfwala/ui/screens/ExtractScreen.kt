package com.pdfwala.ui.screens
import com.pdfwala.util.safeLaunch
import com.pdfwala.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import com.pdfwala.ui.components.PdfThumbnailGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfwala.data.FileManager
import com.pdfwala.data.HistoryManager
import com.pdfwala.data.OperationType
import com.pdfwala.data.PdfFileInfo
import com.pdfwala.domain.operations.PdfSplitter
import com.pdfwala.ui.components.*
import com.pdfwala.util.FileOpener
import com.pdfwala.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for extracting specific pages from PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfSplitter = remember { PdfSplitter() }
    
    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }
    
    // File picker launcher - with PDF MIME type filter and validation
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Validate PDF file
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType != "application/pdf") {
                // Invalid file type - ignore or show error
                return@let
            }
            val fileInfo = FileManager.getFileInfo(context, uri)
            selectedFile = fileInfo
            selectedPages = emptySet()
            
            scope.launch {
                pageCount = pdfSplitter.getPageCount(context, uri)
            }
        }
    }
    
    // Save file launcher (for custom location)
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            val file = selectedFile ?: return@let
            if (selectedPages.isEmpty()) return@let
            
            scope.launch {
                isProcessing = true
                progress = 0f
                
                val outputStream = context.contentResolver.openOutputStream(outputUri)
                if (outputStream != null) {
                    val result = pdfSplitter.extractPages(
                        context = context,
                        inputUri = file.uri,
                        pageNumbers = selectedPages.sorted(),
                        outputStream = outputStream,
                        onProgress = { progress = it }
                    )
                    
                    outputStream.close()

                    result.fold(
                        onSuccess = { count ->
                            resultSuccess = true
                            resultMessage = context.getString(
                                R.string.extract_success_count,
                                count
                            )
                            resultUri = outputUri
                        },
                        onFailure = { error ->
                            resultSuccess = false
                            resultMessage = error.message
                                ?: context.getString(R.string.extract_failed)
                        }
                    )
                } else {
                    resultSuccess = false
                    resultMessage = context.getString(R.string.error_cannot_create_output)
                }
                
                isProcessing = false
                showResult = true
            }
        }
    }
    
    // Function to extract with default location
    fun extractWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val originalFile = selectedFile!!
            val pagesList = selectedPages.sorted()
            
            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = FileManager.generateOutputFileName("extracted")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)
                    
                    if (outputResult != null) {
                        val file = selectedFile!!
                        val extractResult = pdfSplitter.extractPages(
                            context = context,
                            inputUri = file.uri,
                            pageNumbers = pagesList,
                            outputStream = outputResult.outputStream,
                            onProgress = { progress = it }
                        )
                        
                        outputResult.outputStream.close()
                        
                        extractResult.fold(
                            onSuccess = { count ->
                                val outputPath =
                                    "${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}"

                                val successMessage = context.getString(
                                    R.string.extract_success_count,
                                    count
                                )

                                val savedToMessage = context.getString(
                                    R.string.saved_to,
                                    outputPath
                                )

                                Triple(
                                    true,
                                    "$successMessage\n\n$savedToMessage",
                                    outputResult.outputFile.contentUri
                                )
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()

                                Triple(
                                    false,
                                    error.message ?: context.getString(R.string.extract_failed),
                                    null
                                )
                            }
                        )
                    } else {
                        Triple(
                            false,
                            context.getString(R.string.error_cannot_create_output),
                            null
                        )
                    }
                } catch (e: Exception) {
                    Triple(
                        false,
                        e.message ?: context.getString(R.string.extract_failed),
                        null
                    )
                }
            }
            
            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third
            
            // Record in history
            if (resultSuccess && result.third != null) {
                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.EXTRACT,
                    inputFileName = originalFile.name,
                    outputFileUri = result.third,
                    outputFileName = "extracted_${originalFile.name}",
                    details = context.getString(
                        R.string.extract_details_count,
                        pagesList.size
                    )
                )
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.EXTRACT,
                    inputFileName = originalFile.name,
                    errorMessage = result.second
                )
            }
            
            isProcessing = false
            showResult = true
        }
    }
    
    Scaffold(
        topBar = {
            ToolTopBar(
                title = context.getString(R.string.tool_extract_pages),
                onNavigateBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (selectedFile == null) {
                    EmptyState(
                        icon = Icons.Default.ContentCopy,
                        title = context.getString(R.string.no_pdf_selected),
                        subtitle = context.getString(R.string.extract_pages_select_subtitle),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Selected file info
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedFile!!.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "${pluralStringResource(
                                            R.plurals.label_pages_count,
                                            pageCount,
                                            pageCount
                                        )} • ${selectedFile!!.formattedSize}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                                IconButton(onClick = { 
                                    selectedFile = null
                                    selectedPages = emptySet()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.action_remove),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Selection controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.select_pages_count,
                                    selectedPages.size,
                                    pageCount
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Row {
                                TextButton(
                                    onClick = { selectedPages = (1..pageCount).toSet() }
                                ) {
                                    Text(stringResource(R.string.action_select_all))
                                }
                                TextButton(
                                    onClick = { selectedPages = emptySet() }
                                ) {
                                    Text(stringResource(R.string.action_clear))
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Page grid
                        PdfThumbnailGrid(
                            uri = selectedFile!!.uri,
                            pageCount = pageCount,
                            selectedPages = selectedPages,
                            onPageSelected = { pageNum ->
                                selectedPages = if (pageNum in selectedPages) {
                                    selectedPages - pageNum
                                } else {
                                    selectedPages + pageNum
                                }
                            },
                            columns = 4,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                        // Save location option
                        SaveLocationSelector(
                            useCustomLocation = useCustomLocation,
                            onUseCustomLocationChange = { useCustomLocation = it }
                        )
                    }
                }
                
                // Progress overlay
                if (isProcessing) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                OperationProgress(
                                    progress = progress,
                                    message = context.getString(R.string.extracting_pages)
                                )
                            }
                        }
                    }
                }
            }
            
            // Bottom action area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (selectedFile == null) {
                        ActionButton(
                            text = stringResource(R.string.action_select_pdf),
                            onClick = {
                                pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                            },
                            icon = Icons.Default.FolderOpen
                        )
                    } else {
                        ActionButton(
                            text = stringResource(
                                R.string.extract_pages_count,
                                selectedPages.size
                            ),
                            onClick = {
                                if (useCustomLocation) {
                                    val fileName = FileManager.generateOutputFileName("extracted")
                                    savePdfLauncher.safeLaunch(fileName, context)
                                } else {
                                    extractWithDefaultLocation()
                                }
                            },
                            enabled = selectedPages.isNotEmpty(),
                            isLoading = isProcessing,
                            icon = Icons.Default.ContentCopy
                        )
                    }
                }
            }
        }
    }
    
    // Result dialog with View option
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) {
                context.getString(R.string.extraction_complete)
            } else {
                context.getString(R.string.extract_failed)
            },
            message = resultMessage,
            onDismiss = { 
                showResult = false
                resultUri = null
            },
            onAction = resultUri?.let { uri ->
                { scope.launch(Dispatchers.IO) { FileOpener.openPdf(context, uri) } }
            },
            actionText = stringResource(R.string.action_open_pdf)
        )
    }
}

