package com.bgmarif.minichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AppColors = darkColorScheme(
    primary = Color(0xFF8AA4FF),
    onPrimary = Color(0xFF07112F),
    primaryContainer = Color(0xFF172554),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF6FE3C1),
    background = Color(0xFF080A0F),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF0E1118),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF171B24),
    onSurfaceVariant = Color(0xFFA9B0C0),
    outline = Color(0xFF343B4B),
    error = Color(0xFFFF8A80)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(Modifier.fillMaxSize()) {
                    MiniChatV12App()
                }
            }
        }
    }
}

@Composable
private fun MiniChatApp(vm: MiniChatViewModel = viewModel()) {
    when {
        vm.session == null -> AuthScreen(vm)
        vm.needsHandle -> HandleSetupScreen(vm)
        vm.contact != null -> ChatScreen(vm)
        else -> MainShell(vm)
    }
}

@Composable
private fun AuthScreen(vm: MiniChatViewModel) {
    var signup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Lock, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("MiniChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Private by default. Encrypted on-device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

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
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (signup) vm.signUp(email, password, handle)
                    else vm.login(email, password)
                },
                enabled = !vm.busy && email.isNotBlank() && password.isNotBlank() && (!signup || handle.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (signup) "Create secure account" else "Sign in")
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { signup = !signup }) {
                    Text(if (signup) "Already have an account" else "Create account")
                }
                if (!signup) {
                    TextButton(onClick = { vm.requestPasswordReset(email) }) {
                        Text("Forgot password?")
                    }
                }
            }

            StatusText(vm.status)

            Text(
                "Your encryption identity stays on this phone. Supabase only stores public keys, routing metadata and ciphertext.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HandleSetupScreen(vm: MiniChatViewModel) {
    var handle by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(38.dp))
            Text("Finish MiniChat setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Your account is signed in. Pick the public handle people will use to find you.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = it.removePrefix("@").lowercase() },
                prefix = { Text("@") },
                label = { Text("MiniChat handle") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.completeHandle(handle) },
                enabled = !vm.busy && handle.length >= 3,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Create encrypted identity")
            }
            TextButton(onClick = vm::logout, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Sign out")
            }
            StatusText(vm.status)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(vm: MiniChatViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (vm.tab) {
                                MiniChatViewModel.Tab.CHATS -> "MiniChat"
                                MiniChatViewModel.Tab.PEOPLE -> "Find people"
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
                MiniChatViewModel.Tab.CHATS -> ChatsScreen(vm)
                MiniChatViewModel.Tab.PEOPLE -> PeopleScreen(vm)
                MiniChatViewModel.Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun ChatsScreen(vm: MiniChatViewModel) {
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshConversations()
            delay(5000)
        }
    }

    if (vm.conversations.isEmpty()) {
        EmptyState(
            icon = { Icon(Icons.Outlined.ChatBubbleOutline, null, modifier = Modifier.size(42.dp)) },
            title = "No conversations yet",
            body = "Find someone by handle and start an encrypted chat."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        items(vm.conversations, key = { it.contact.userId }) { conversation ->
            ConversationRow(conversation) { vm.openChat(conversation.contact) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarBubble(conversation.contact.handle)
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
                        conversation.lastAt.toTimeLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.lastMessage?.body ?: "Encrypted conversation",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Text(
                                conversation.unreadCount.coerceAtMost(99).toString(),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleScreen(vm: MiniChatViewModel) {
    var query by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        delay(300)
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
        StatusText(vm.status)
        Spacer(Modifier.height(8.dp))

        if (query.length < 2) {
            EmptyState(
                icon = { Icon(Icons.Outlined.PersonSearch, null, modifier = Modifier.size(42.dp)) },
                title = "Find people securely",
                body = "Search a MiniChat handle. Public profile data contains only the handle and encryption public keys."
            )
        } else if (vm.peopleResults.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(38.dp)) },
                title = "No matches",
                body = "Try a different handle."
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(vm.peopleResults, key = { it.userId }) { profile ->
                    Surface(onClick = { vm.openChat(profile) }, color = Color.Transparent) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarBubble(profile.handle)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("@${profile.handle}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "End-to-end encryption available",
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
private fun SettingsScreen(vm: MiniChatViewModel) {
    var editHandle by remember { mutableStateOf(false) }
    var newHandle by remember(vm.ownProfile?.handle) { mutableStateOf(vm.ownProfile?.handle.orEmpty()) }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarBubble(vm.ownProfile?.handle.orEmpty())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("@${vm.ownProfile?.handle.orEmpty()}", fontWeight = FontWeight.Bold)
                        Text("Single-device encrypted identity", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { editHandle = true }) { Icon(Icons.Outlined.Edit, "Edit handle") }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Text("Security fingerprint", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    vm.ownFingerprint(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.ownFingerprint())) }) {
                    Icon(Icons.Outlined.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy fingerprint")
                }
                Text(
                    "Compare fingerprints out-of-band for stronger identity verification. A contact key change is blocked until you explicitly trust it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Privacy model", fontWeight = FontWeight.SemiBold)
                Text(
                    "Text, reply previews, filenames and attachment keys are encrypted before upload. The server can still see account IDs, message timestamps, ciphertext sizes and who is talking to whom.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(onClick = vm::logout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("Sign out")
        }

        StatusText(vm.status)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatScreen(vm: MiniChatViewModel) {
    val contact = vm.contact ?: return
    var input by remember(contact.userId) { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.sendFile(uri)
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
        if (vm.messages.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(vm.messages.lastIndex) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::closeChat) { Icon(Icons.Outlined.ArrowBack, "Back") }
                },
                title = {
                    Column {
                        Text("@${contact.handle}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "E2EE · ${vm.contactFingerprint()?.take(14).orEmpty()}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
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
                                text = { Text("Block @${contact.handle}") },
                                leadingIcon = { Icon(Icons.Outlined.Block, null) },
                                onClick = {
                                    menuOpen = false
                                    vm.blockCurrentContact()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                if (vm.messages.any { it.securityBlocked && !it.fromMe }) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Security key changed. Verify the fingerprint before trusting new messages.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = vm::acceptContactKeyChange) { Text("Trust") }
                    }
                }

                vm.replyingTo?.let { reply ->
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Reply, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                reply.body,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { vm.setReply(null) }) { Text("Cancel") }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !vm.busy
                    ) {
                        Icon(Icons.Outlined.AttachFile, "Attach any file")
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f),
                        maxLines = 5
                    )
                    IconButton(
                        onClick = {
                            val value = input
                            input = ""
                            vm.sendText(value)
                        },
                        enabled = input.isNotBlank() && !vm.busy
                    ) {
                        Icon(Icons.Outlined.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            StatusText(vm.status)
            if (vm.messages.isEmpty()) {
                EmptyState(
                    icon = { Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(42.dp)) },
                    title = "Encrypted conversation",
                    body = "Messages and files are encrypted on your phone before upload."
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(vm.messages, key = { it.db.id }) { message ->
                        MessageBubble(
                            message = message,
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: DecryptedMessage,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    var actions by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Card(
                modifier = Modifier
                    .widthIn(max = 320.dp)
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
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (message.fromMe) 18.dp else 5.dp,
                    bottomEnd = if (message.fromMe) 5.dp else 18.dp
                )
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    message.payload?.replyPreview?.let { preview ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.35f)
                        ) {
                            Text(
                                preview,
                                modifier = Modifier.padding(8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                    }

                    if (message.payload?.type == "file") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.InsertDriveFile, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                message.payload.fileName ?: "File",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            message.body.substringAfter("·", "Encrypted attachment").trim(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!message.securityBlocked) {
                            Row {
                                TextButton(onClick = onOpen) { Text("Open") }
                                TextButton(onClick = onShare) {
                                    Icon(Icons.Outlined.Share, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Share")
                                }
                            }
                        }
                    } else {
                        Text(message.body)
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            message.db.createdAt.toTimeLabel(),
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
                    onClick = { actions = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun AvatarBubble(handle: String) {
    val letter = handle.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun StatusText(value: String) {
    if (value.isBlank()) return
    Text(
        value,
        modifier = Modifier.padding(vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (value.contains("wrong", true) || value.contains("failed", true) || value.contains("error", true))
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    body: String
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun String?.toTimeLabel(): String {
    if (this.isNullOrBlank()) return ""
    return runCatching {
        val instant = Instant.parse(this)
        DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault("")
}
