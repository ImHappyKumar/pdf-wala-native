package com.pdfwala.ui.screens
import com.pdfwala.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.pdfwala.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfwala.data.FileManager
import com.pdfwala.data.PdfFileInfo
import com.pdfwala.domain.operations.TextExtractionOptions
import com.pdfwala.domain.operations.TextExtractor
import com.pdfwala.ui.components.*
import kotlinx.coroutines.launch

/**
 * Screen for extracting text from PDFs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractTextScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textExtractor = remember { TextExtractor() }
    
    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var hasExtractableText by remember { mutableStateOf<Boolean?>(null) }
    var previewText by remember { mutableStateOf("") }
    var addPageBreaks by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    
    // File picker launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFile = FileManager.getFileInfo(context, uri)
            hasExtractableText = null
            previewText = ""
            
            // Check if PDF has extractable text
            scope.launch {
                isChecking = true
                hasExtractableText = textExtractor.hasExtractableText(context, uri)
                
                if (hasExtractableText == true) {
                    // Get preview of first page
                    previewText = textExtractor.extractFromPage(context, uri, 1)
                        .take(500)
                        .let { if (it.length == 500) "$it..." else it }
                }
                isChecking = false
            }
        }
    }
    
    // Save file launcher
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { saveUri ->
            val file = selectedFile ?: return@let
            
            scope.launch {
                isProcessing = true
                progress = 0f
                
                context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                    val options = TextExtractionOptions(
                        addPageBreaks = addPageBreaks,
                        preserveLineBreaks = true
                    )
                    
                    val result = textExtractor.extractToTextFile(
                        context = context,
                        inputUri = file.uri,
                        outputStream = outputStream,
                        options = options,
                        onProgress = { progress = it }
                    )
                    
                    result.fold(
                        onSuccess = { extractResult ->
                            resultSuccess = true
                            resultMessage = context.getString(
                                R.string.extract_success_message,
                                extractResult.characterCount,
                                extractResult.wordCount,
                                extractResult.pageCount
                            )
                            selectedFile = null
                        },
                        onFailure = { error ->
                            resultSuccess = false
                            resultMessage = error.message
                                ?: context.getString(R.string.extract_failed)
                        }
                    )
                } ?: run {
                    resultSuccess = false
                    resultMessage = context.getString(R.string.error_cannot_create_output)
                }
                
                isProcessing = false
                showResult = true
            }
        }
    }
    
    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.extract_title),
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
                        icon = Icons.Default.TextFields,
                        title = stringResource(R.string.no_pdf_selected),
                        subtitle = stringResource(R.string.extract_no_pdf_subtitle),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        // Selected file info
                        item {
                            FileItemCard(
                                fileName = selectedFile!!.name,
                                fileSize = selectedFile!!.formattedSize,
                                onRemove = { 
                                    selectedFile = null
                                    hasExtractableText = null
                                    previewText = ""
                                }
                            )
                        }
                        
                        // Text availability status
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = when (hasExtractableText) {
                                        true -> MaterialTheme.colorScheme.primaryContainer
                                        false -> MaterialTheme.colorScheme.errorContainer
                                        null -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isChecking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = when (hasExtractableText) {
                                                true -> Icons.Default.CheckCircle
                                                false -> Icons.Default.Warning
                                                null -> Icons.Default.HourglassEmpty
                                            },
                                            contentDescription = null
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = when {
                                                isChecking -> stringResource(R.string.extract_analyzing)
                                                hasExtractableText == true -> stringResource(R.string.extract_text_found)
                                                hasExtractableText == false -> stringResource(R.string.extract_no_text_detected)
                                                else -> stringResource(R.string.extract_checking)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        if (hasExtractableText == false) {
                                            Text(
                                                text = stringResource(R.string.extract_scanned_pdf),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Preview
                        if (previewText.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.extract_preview),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = previewText,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                        
                        // Options
                        item {
                            Text(
                                text = stringResource(R.string.extract_options),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.extract_add_page_breaks),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = stringResource(R.string.extract_page_breaks_description),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = addPageBreaks,
                                        onCheckedChange = { addPageBreaks = it }
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Progress overlay
                if (isProcessing) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                            .align(Alignment.Center)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OperationProgress(
                                progress = progress,
                                message = stringResource(R.string.extract_progress)
                            )
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
                            text = stringResource(R.string.extract_to_text_file),
                            onClick = {
                                val baseName = selectedFile!!.name.removeSuffix(".pdf")
                                saveFileLauncher.safeLaunch("${baseName}.txt", context)
                            },
                            enabled = hasExtractableText == true,
                            isLoading = isProcessing,
                            icon = Icons.Default.TextFields
                        )
                    }
                }
            }
        }
    }
    
    // Result dialog
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) {
                stringResource(R.string.extract_complete)
            } else {
                stringResource(R.string.extract_failed)
            },
            message = resultMessage,
            onDismiss = { showResult = false }
        )
    }
}
