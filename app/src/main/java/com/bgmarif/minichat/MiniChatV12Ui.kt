package com.bgmarif.minichat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val V12Colors = darkColorScheme(
    primary = Color(0xFF8FA8FF),
    onPrimary = Color(0xFF07122F),
    primaryContainer = Color(0xFF17285B),
    onPrimaryContainer = Color(0xFFE1E7FF),
    secondary = Color(0xFF70E2C0),
    background = Color(0xFF07090D),
    onBackground = Color(0xFFF2F3F7),
    surface = Color(0xFF0D1016),
    onSurface = Color(0xFFF2F3F7),
    surfaceVariant = Color(0xFF151A23),
    onSurfaceVariant = Color(0xFFAEB5C5),
    outline = Color(0xFF2D3544),
    error = Color(0xFFFF8A80)
)

@Composable
fun MiniChatV12App(vm: MiniChatViewModel = viewModel()) {
    MaterialTheme(colorScheme = V12Colors) {
        Surface(Modifier.fillMaxSize()) {
            when {
                vm.session == null -> AuthScreen12(vm)
                vm.identityMismatch -> IdentityMismatchScreen12(vm)
                vm.needsHandle -> HandleSetup12(vm)
                vm.contact != null -> ChatScreen12(vm)
                else -> MainShell12(vm)
            }
        }
    }
}

@Composable
private fun AuthScreen12(vm: MiniChatViewModel) {
    var signup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.Lock, null, Modifier.padding(18.dp).size(32.dp))
            }
            Text("MiniChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Private messaging, without the prototype feel.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (signup) {
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it.removePrefix("@").lowercase() },
                    label = { Text("Handle") },
                    prefix = { Text("@") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (signup) vm.signUp(email, password, handle) else vm.login(email, password)
                },
                enabled = !vm.busy && email.isNotBlank() && password.isNotBlank() && (!signup || handle.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (signup) "Create secure account" else "Sign in")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { signup = !signup }) {
                    Text(if (signup) "Already have an account" else "Create account")
                }
                if (!signup) {
                    TextButton(onClick = { vm.requestPasswordReset(email) }) { Text("Forgot password?") }
                }
            }
            Status12(vm.status)
        }
    }
}

@Composable
private fun HandleSetup12(vm: MiniChatViewModel) {
    var handle by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.secondary)
            Text("Choose your handle", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("People will search this handle to find you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = it.removePrefix("@").lowercase() },
                label = { Text("MiniChat handle") },
                prefix = { Text("@") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.completeHandle(handle) },
                enabled = !vm.busy && handle.length >= 3,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Continue") }
            TextButton(onClick = vm::logout, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Sign out")
            }
            Status12(vm.status)
        }
    }
}

@Composable
private fun IdentityMismatchScreen12(vm: MiniChatViewModel) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.error)
                Text("Encryption identity changed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "This phone does not have the same private identity already published for this account. MiniChat will not silently overwrite it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Published: ${vm.serverIdentityFingerprint().orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Text("This phone: ${vm.localIdentityFingerprint().orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::resetEncryptionIdentity, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset account to this phone")
                }
                Text(
                    "Resetting can make older encrypted history unreadable on this phone and contacts will see a key-change warning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = vm::logout, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out instead")
                }
                Status12(vm.status)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell12(vm: MiniChatViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (vm.tab) {
                                MiniChatViewModel.Tab.CHATS -> "Chats"
                                MiniChatViewModel.Tab.PEOPLE -> "People"
                                MiniChatViewModel.Tab.SETTINGS -> "Settings"
                            },
                            fontWeight = FontWeight.Bold
                        )
                        if (vm.tab == MiniChatViewModel.Tab.CHATS) {
                            Text(
                                "@${vm.ownProfile?.handle.orEmpty()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = vm.tab == MiniChatViewModel.Tab.CHATS,
                    onClick = { vm.selectTab(MiniChatViewModel.Tab.CHATS) },
                    icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                    label = { Text("Chats") }
                )
                NavigationBarItem(
                    selected = vm.tab == MiniChatViewModel.Tab.PEOPLE,
                    onClick = { vm.selectTab(MiniChatViewModel.Tab.PEOPLE) },
                    icon = { Icon(Icons.Outlined.PersonSearch, null) },
                    label = { Text("People") }
                )
                NavigationBarItem(
                    selected = vm.tab == MiniChatViewModel.Tab.SETTINGS,
                    onClick = { vm.selectTab(MiniChatViewModel.Tab.SETTINGS) },
                    icon = { Icon(Icons.Outlined.Settings, null) },
                    label = { Text("Settings") }
                )
            }
        },
        floatingActionButton = {
            if (vm.tab == MiniChatViewModel.Tab.CHATS) {
                FloatingActionButton(onClick = { vm.selectTab(MiniChatViewModel.Tab.PEOPLE) }) {
                    Icon(Icons.Outlined.PersonSearch, "Find someone")
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (vm.tab) {
                MiniChatViewModel.Tab.CHATS -> Chats12(vm)
                MiniChatViewModel.Tab.PEOPLE -> People12(vm)
                MiniChatViewModel.Tab.SETTINGS -> Settings12(vm)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chats12(vm: MiniChatViewModel) {
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshConversations()
            delay(5000)
        }
    }

    if (vm.conversations.isEmpty()) {
        Empty12(Icons.Outlined.ChatBubbleOutline, "No chats yet", "Find someone by handle to start an encrypted chat.")
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(vm.conversations, key = { it.contact.userId }) { conversation ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { vm.openChat(conversation.contact) },
                            onLongClick = { deleteTarget = conversation }
                        ),
                    color = Color.Transparent
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar12(conversation.contact.handle)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "@${conversation.contact.handle}",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    time12(conversation.lastAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    conversation.lastMessage?.body ?: "Encrypted conversation",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (conversation.unreadCount > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                        Text(
                                            conversation.unreadCount.coerceAtMost(99).toString(),
                                            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
            }
        }
    }

    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text("Delete chat?") },
            text = {
                Text(
                    "Delete the local chat with @${conversation.contact.handle}? Old messages stay hidden after sync. A new message can start the chat again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteConversation(conversation.contact)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun People12(vm: MiniChatViewModel) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(250)
        vm.searchPeople(query)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.removePrefix("@") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            label = { Text("Search @handle") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Status12(vm.status)
        Spacer(Modifier.height(8.dp))

        if (query.length < 2) {
            Empty12(Icons.Outlined.PersonSearch, "Find people", "Search a MiniChat handle.")
        } else if (vm.peopleResults.isEmpty()) {
            Empty12(Icons.Outlined.Search, "No matches", "Try another handle.")
        } else {
            LazyColumn {
                items(vm.peopleResults, key = { it.userId }) { profile ->
                    Surface(onClick = { vm.openChat(profile) }, color = Color.Transparent) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar12(profile.handle)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("@${profile.handle}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Tap to message",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Outlined.ChatBubbleOutline, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings12(vm: MiniChatViewModel) {
    var editHandle by remember { mutableStateOf(false) }
    var newHandle by remember(vm.ownProfile?.handle) { mutableStateOf(vm.ownProfile?.handle.orEmpty()) }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar12(vm.ownProfile?.handle.orEmpty())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("@${vm.ownProfile?.handle.orEmpty()}", fontWeight = FontWeight.Bold)
                    Text("Encrypted device identity", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { editHandle = true }) { Icon(Icons.Outlined.Edit, "Edit handle") }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Security fingerprint", fontWeight = FontWeight.SemiBold)
                Text(vm.ownFingerprint(), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.ownFingerprint())) }) {
                    Icon(Icons.Outlined.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy fingerprint")
                }
            }
        }

        if (vm.blockedProfiles.isNotEmpty()) {
            Text("Blocked", fontWeight = FontWeight.SemiBold)
            vm.blockedProfiles.forEach { profile ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("@${profile.handle}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.unblock(profile) }) { Text("Unblock") }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = vm::logout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("Sign out")
        }
        Status12(vm.status)
        Spacer(Modifier.height(8.dp))
    }

    if (editHandle) {
        AlertDialog(
            onDismissRequest = { editHandle = false },
            title = { Text("Change handle") },
            text = {
                OutlinedTextField(
                    value = newHandle,
                    onValueChange = { newHandle = it.removePrefix("@").lowercase() },
                    prefix = { Text("@") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateHandle(newHandle)
                    editHandle = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editHandle = false }) { Text("Cancel") } }
        )
    }
}

private enum class AttachmentKind12 { CAMERA, PHOTO, VIDEO, DOCUMENT }
private data class PendingAttachment12(val uri: Uri, val kind: AttachmentKind12)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen12(vm: MiniChatViewModel) {
    val contact = vm.contact ?: return
    val context = LocalContext.current
    var input by remember(contact.userId) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var attachmentSheet by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingAttachment12?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = PendingAttachment12(uri, AttachmentKind12.DOCUMENT)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = PendingAttachment12(uri, AttachmentKind12.PHOTO)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = PendingAttachment12(uri, AttachmentKind12.VIDEO)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { pending = PendingAttachment12(it, AttachmentKind12.CAMERA) }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(contact.userId) {
        while (true) {
            vm.refreshMessages()
            delay(2500)
        }
    }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(vm.messages.lastIndex) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::closeChat) { Icon(Icons.Outlined.ArrowBack, "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar12(contact.handle, 38)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("@${contact.handle}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "End-to-end encrypted",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy security fingerprint") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                                onClick = {
                                    vm.contactFingerprint()?.let { clipboard.setText(AnnotatedString(it)) }
                                    menuOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete chat") },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                                onClick = { menuOpen = false; confirmDelete = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Block @${contact.handle}") },
                                leadingIcon = { Icon(Icons.Outlined.Block, null) },
                                onClick = { menuOpen = false; vm.blockCurrentContact() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Column(
                Modifier.background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (vm.messages.any { it.securityBlocked && !it.fromMe }) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Security key changed",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = vm::acceptContactKeyChange) { Text("Verify / Trust") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                vm.replyingTo?.let { reply ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Reply, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                reply.body,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { vm.setReply(null) }) { Text("×") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { attachmentSheet = true }, enabled = !vm.busy) {
                            Icon(Icons.Outlined.AttachFile, "Attach")
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Message") },
                            modifier = Modifier.weight(1f),
                            maxLines = 4
                        )
                        IconButton(
                            onClick = {
                                val text = input
                                input = ""
                                vm.sendText(text)
                            },
                            enabled = input.isNotBlank() && !vm.busy
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                Icon(
                                    Icons.Outlined.Send,
                                    "Send",
                                    modifier = Modifier.padding(9.dp).size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (vm.messages.isEmpty()) {
                Empty12(
                    Icons.Outlined.Lock,
                    "Encrypted conversation",
                    "Messages appear from local cache first, then sync in the background."
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(vm.messages, key = { it.db.id }) { message ->
                        Bubble12(
                            message,
                            onReply = { vm.setReply(message) },
                            onDelete = { vm.deleteForMe(message.db.id) },
                            onOpen = { vm.openFile(message) },
                            onShare = { vm.shareFile(message) }
                        )
                    }
                }
            }
        }
    }

    if (attachmentSheet) {
        AttachmentSheet12(
            onDismiss = { attachmentSheet = false },
            onCamera = {
                attachmentSheet = false
                val uri = createCameraUri12(context)
                cameraUri = uri
                cameraLauncher.launch(uri)
            },
            onGallery = {
                attachmentSheet = false
                photoPicker.launch(arrayOf("image/*"))
            },
            onVideo = {
                attachmentSheet = false
                videoPicker.launch(arrayOf("video/*"))
            },
            onDocument = {
                attachmentSheet = false
                documentPicker.launch(arrayOf("*/*"))
            }
        )
    }

    pending?.let { item ->
        AttachmentPreview12(
            item = item,
            context = context,
            onCancel = { pending = null },
            onSend = {
                vm.sendFile(item.uri)
                pending = null
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    "Messages are removed from this device and stay hidden after sync. A new message from @${contact.handle} can start the chat again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCurrentChat()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet12(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onVideo: () -> Unit,
    onDocument: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Send something", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Choose what to attach. MiniChat encrypts it before upload.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AttachTile12("Camera", Icons.Outlined.CameraAlt, Modifier.weight(1f), onCamera)
                AttachTile12("Gallery", Icons.Outlined.PhotoLibrary, Modifier.weight(1f), onGallery)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AttachTile12("Video", Icons.Outlined.VideoLibrary, Modifier.weight(1f), onVideo)
                AttachTile12("Document", Icons.Outlined.Description, Modifier.weight(1f), onDocument)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AttachTile12(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            Modifier.padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, Modifier.padding(12.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AttachmentPreview12(
    item: PendingAttachment12,
    context: Context,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val name = remember(item.uri) { displayName12(context, item.uri) ?: "attachment" }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Send $name?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.kind == AttachmentKind12.PHOTO || item.kind == AttachmentKind12.CAMERA) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setImageURI(item.uri)
                                }
                            },
                            update = { it.setImageURI(item.uri) },
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.15f)
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                if (item.kind == AttachmentKind12.VIDEO) Icons.Outlined.VideoLibrary
                                else Icons.Outlined.InsertDriveFile,
                                null,
                                modifier = Modifier.size(46.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(
                    "Encrypted on your phone before upload.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onSend) {
                Icon(Icons.Outlined.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble12(
    message: DecryptedMessage,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    var actions by remember { mutableStateOf(false) }
    val mime = message.payload?.mimeType.orEmpty()
    val isImage = mime.startsWith("image/")
    val isVideo = mime.startsWith("video/")

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Card(
                modifier = Modifier
                    .widthIn(max = 330.dp)
                    .combinedClickable(
                        onClick = {
                            if (message.payload?.type == "file" && !message.securityBlocked) onOpen()
                        },
                        onLongClick = { actions = true }
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.fromMe) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (message.fromMe) 20.dp else 5.dp,
                    bottomEnd = if (message.fromMe) 5.dp else 20.dp
                )
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    message.payload?.replyPreview?.let { preview ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
                        ) {
                            Text(
                                preview,
                                Modifier.padding(8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                    }

                    if (message.payload?.type == "file") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when {
                                    isImage -> Icons.Outlined.Image
                                    isVideo -> Icons.Outlined.VideoLibrary
                                    else -> Icons.Outlined.InsertDriveFile
                                },
                                null
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    message.payload.fileName ?: "File",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (isImage) "Photo" else if (isVideo) "Video" else "Document",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (!message.securityBlocked) {
                            Row {
                                TextButton(onClick = onOpen) { Text("Open") }
                                TextButton(onClick = onShare) {
                                    Icon(Icons.Outlined.Share, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Share")
                                }
                            }
                        }
                    } else {
                        Text(message.body)
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            time12(message.db.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (message.fromMe && message.deliveryLabel.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                message.deliveryLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.db.readAt != null) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                if (!message.securityBlocked) {
                    DropdownMenuItem(
                        text = { Text("Reply") },
                        leadingIcon = { Icon(Icons.Outlined.Reply, null) },
                        onClick = { actions = false; onReply() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete for me") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    onClick = { actions = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun Avatar12(handle: String, size: Int = 46) {
    val letter = handle.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Surface(Modifier.size(size.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Empty12(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(icon, null, Modifier.padding(18.dp).size(34.dp))
            }
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Status12(text: String) {
    if (text.isNotBlank()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun createCameraUri12(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
}

private fun displayName12(context: Context, uri: Uri): String? =
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

private fun time12(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: return ""
    return DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(instant)
}
