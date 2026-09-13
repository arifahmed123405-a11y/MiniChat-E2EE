package com.bgmarif.minichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { MiniChatApp() }
            }
        }
    }
}

@Composable
private fun MiniChatApp(vm: MiniChatViewModel = viewModel()) {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(vm) {
        val unsubscribe = vm.subscribe { revision++ }
        onDispose { unsubscribe() }
    }
    revision // force observation

    when {
        vm.session == null -> AuthScreen(vm)
        vm.contact == null -> HomeScreen(vm)
        else -> ChatScreen(vm)
    }
}

@Composable
private fun AuthScreen(vm: MiniChatViewModel) {
    var signup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("MiniChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("End-to-end encrypted mini messenger")
            if (signup) {
                OutlinedTextField(handle, { handle = it }, label = { Text("Handle") }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { if (signup) vm.signUp(email, password, handle) else vm.login(email, password) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (signup) "Create account" else "Sign in") }
            TextButton(onClick = { signup = !signup }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (signup) "Already have an account" else "Create a new account")
            }
            if (vm.status.isNotBlank()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HomeScreen(vm: MiniChatViewModel) {
    var handle by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("MiniChat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("@${vm.ownProfile?.handle ?: "…"}")
            }
            TextButton(onClick = vm::logout) { Text("Logout") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Start a private chat", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    handle, { handle = it.removePrefix("@") },
                    label = { Text("User handle") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Button(onClick = { vm.searchHandle(handle) }, enabled = handle.isNotBlank() && !vm.busy) {
                    Text("Open chat")
                }
            }
        }

        Text("Security", fontWeight = FontWeight.SemiBold)
        Text(
            "Messages and files are encrypted on your phone before upload. The server stores ciphertext, routing IDs, timestamps, and encrypted blobs.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (vm.status.isNotBlank()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: MiniChatViewModel) {
    var input by remember { mutableStateOf("") }
    val contact = vm.contact ?: return
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.sendFile(uri)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
                title = {
                    Column {
                        Text("@${contact.handle}")
                        Text("E2EE", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { TextButton(onClick = vm::closeChat) { Text("Back") } }
            )
        },
        bottomBar = {
            Column {
                if (vm.status.contains("Security key changed")) {
                    AssistChip(
                        onClick = vm::acceptContactKeyChange,
                        label = { Text("Trust new security key") },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !vm.busy) { Text("+") }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )
                    Button(onClick = { vm.sendText(input); input = "" }, enabled = input.isNotBlank() && !vm.busy) {
                        Text("Send")
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (vm.status.isNotBlank()) {
                Text(vm.status, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(vm.messages, key = { it.db.id }) { msg ->
                    MessageBubble(msg, onOpen = { vm.openFile(msg) })
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: DecryptedMessage, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromMe) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp).then(
                if (msg.payload?.type == "file" && !msg.securityBlocked) Modifier.clickable(onClick = onOpen) else Modifier
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(msg.body)
                if (msg.payload?.type == "file" && !msg.securityBlocked) {
                    Text("Tap to decrypt & open", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
