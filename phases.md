# Xed-Editor PRO — AI Agent: Phases & Architecture

This file is the single source of truth for the in-app **AI Agent** feature. It exists so a fresh
session (with no memory of prior work) can fully understand the design, what's done, and what's next.

---

## 0. The user's original request (paraphrased, kept faithful)

> Add an AI Agent inside the IDE, like Claude Code / Kiro / OpenRouter. Implement API-key chat with a
> per-session token limit depending on the selected model. OpenCode supports multiple API keys and
> lets the model use tools (read/write/etc.) — implement that "perfectly".
>
> UI: an **AI Agent button above "Git"** in the drawer rail. On open, if there's no valid API key,
> show a dialog to add one. After verifying, save it (add Settings to manage AI models/keys/providers).
> The chat window should auto-**maximize** (we already added a maximize button at the bottom of the
> drawer). It should look like **Kiro web**: chat box at the bottom that also shows the **current
> selected directory**, a **Change model** button opening a dropdown of the key's **real-time models**
> (selectable + default), and beside it **"• % Used"** (each model has a per-session token limit). A
> **send** button on the right. The main middle area is the conversation.
>
> Provide **all tools exactly like Claude Code**, including a **permission dialog** before executing
> commands/etc., with an option to **never ask again**. The AI should use tools autonomously. Show the
> **step-by-step "thinking"** process like Claude Code CLI.
>
> Give the AI a **background terminal** it can use quietly; a **notification** shows whether it's using
> it; the user can **kill** the terminal (AI is notified) and the AI can start it again.
>
> **Top-left chat button** opens a dialog of all chat **sessions** to switch between (real, individual,
> without stopping the running one). **Save chats** so nothing is lost.
>
> Later clarification: Kiro has no single public HTTP API endpoint for its `ksk_` key (it's consumed by
> the kiro-cli binary). So go **provider-agnostic**: support **Gemini, OpenAI/Codex, OpenRouter, and a
> Custom key**, plus any other popular OpenAI-compatible provider. Build it like Claude Code.

---

## 1. Key architectural decision (WHY it's built this way)

Kiro's API key (`ksk_…`) is **not** a documented public REST endpoint — it's used by the `kiro-cli`
binary, which runs its own agent. So we cannot "call the Kiro API with tools" directly.

Instead we use the **OpenAI-compatible standard**, which is what OpenRouter/OpenAI/Gemini(compat)/
Bedrock all speak and is exactly how OpenCode/Claude Code give a key tool access:

- `GET  {baseUrl}/models` — verify key + list models (the live model dropdown).
- `POST {baseUrl}/chat/completions` — chat with a `tools` array (JSON schemas). The model replies with
  `tool_calls`; **the app executes the tool locally** (this is where the permission dialog lives) and
  sends the result back as a `tool` message. Loop until the model returns final text.

So **tools always run client-side, in the app**; the key only lets the model *request* them. This is
the core mechanism the user asked about ("how do custom keys access read/write").

A literal "Kiro" provider isn't a preset because there's no public endpoint; the **Custom** provider
(any OpenAI-compatible base URL) covers a Kiro→OpenAI gateway or anything else.

---

## 2. Files (where to look for what) — all under `core/main/src/main/java/com/rk/ai/`

- **AiProviders.kt** — `AiProvider` data class + presets (`OPENROUTER`, `OPENAI`, `GEMINI`, `CUSTOM`),
  `AiProviders.all`, `byId()`, and `contextWindow(model)` (token window estimate used for "% used").
  Add new providers here.
- **AiPrefs.kt** — all persistence via the app's `Preference` store: `getKey/setKey/hasKey` (per
  provider id), `selectedProviderId`, `selectedModel`, `customBaseUrl`, `baseUrl(provider)`, and the
  per-tool permission `Policy` (ASK/ALWAYS/NEVER) via `getPolicy/setPolicy`. Keys never leave the
  device except as the `Authorization: Bearer` header to that provider.
- **AiMessage.kt** — `AiMessage(role, content, toolCalls, toolCallId, ui)` and `AiToolCall(id, name,
  arguments)`. `ui` is a render hint ("text"/"tool_result"). Mapped to/from OpenAI JSON in AiClient.
- **AiClient.kt** — OkHttp + `org.json` (no new deps). `listModels(provider,key)` and
  `chat(provider,key,model,messages,tools): ChatResult(message,totalTokens)`. Handles request/response
  JSON (incl. building `tool_calls` back into the request, parsing `usage.total_tokens`). This is the
  network layer; SSE streaming will be added here in Phase 2.
- **AiTools.kt** — the tools the model may call. `schemas()` returns the OpenAI tool JSON array;
  `describe(call)` is the human summary for the permission dialog/UI; `execute(call, workingDir)` runs
  them: `read_file`/`write_file`/`list_dir` on the real filesystem (relative paths resolve against the
  project root) and `run_command` in the Linux sandbox via `ShellUtils.runUbuntu` (working dir is
  translated with `ProjectRunner.toSandboxPath`). Add new tools here (schema + execute branch + name).
- **AiViewModel.kt** — the brain. `Session(id,title,messages,totalTokens)`; `sessions` list +
  `currentSessionId`; `provider/model/models` state; `percentUsed`. `send(text,workingDir)` appends the
  user message and runs `runAgentLoop` (max 16 iterations) in `viewModelScope`: chat → if `toolCalls`,
  `askPermission` (a `CompletableDeferred` gate driven by `pendingPermission` + `resolvePermission`),
  `AiTools.execute`, append `tool` result, repeat. `refreshModels()` lists models; `selectProvider/
  selectModel/newSession/selectSession`. Persistence hooks live here (Phase 2: `AiSessionStore`).
- **AiTab.kt** — the UI. `AiTab : DrawerTab` (name "AI", icon `drawables.bolt`; `getIcon()` returns
  `com.rk.icons.Icon` fully-qualified to avoid a clash with the material3 `Icon` composable). `AiScreen`
  obtains the VM via `viewModel<AiViewModel>()`, sets `isDrawerExpanded = true` (auto-maximize), derives
  `workingDir` from the current `FileTreeTab` root (fallback `sandboxHomeDir`). Pieces: `SetupPrompt` +
  `AddKeyDialog` (provider dropdown + key + "Verify & save" → `listModels`); `MessagesList` (user
  bubble / assistant text + tool-call "steps" / tool-result monospace block, "Thinking…" while busy);
  `Composer` (📁 directory + model `DropdownMenu` (live) + "% used" + `OutlinedTextField` + send);
  `SessionsDialog` (top-left chats); `PermissionDialog` (Allow/Deny + "Don't ask again").
- **AiSessionStore.kt** (Phase 2) — on-disk JSON persistence of sessions so chats aren't lost.

### Wiring (outside com.rk.ai)
- **`com.rk.drawer.DrawerViewModel.setupBuiltinServices`** — registers `com.rk.ai.AiTab()` **before**
  `GitTab` so AI sits above Git in the service rail. Service tabs are rebuilt each launch (not
  persisted by DrawerPersistence).
- The rail + content rendering is in **`com.rk.drawer.Drawer.kt`** (serviceTabs loop + `Crossfade`).
- **`com.rk.components.isDrawerExpanded`** (in `ResponsiveDrawer.kt`) is the maximize flag the chat sets.

---

## 3. Phase status

### ✅ Phase 1 (4.1.0) — Foundation (DONE)
Provider-agnostic OpenAI-compatible engine, key management, tools (read/write/list/run_command) with
permission gating, agent tool-call loop, the AI drawer tab above Git, chat UI (model dropdown, % used,
directory, send, sessions list, permission dialog). Non-streaming; in-memory sessions.

### 🔶 Phase 2 — Persistence + streaming + dedicated terminal + settings (IN PROGRESS)
- [x] **On-disk chat persistence** (`AiSessionStore`) — chats survive app restarts (4.1.1).
- [x] **SSE token streaming** (4.1.2) — `AiClient.chatStream()` parses `data:` SSE lines (content +
      tool-call argument fragments by index) and calls `onDelta`; `AiViewModel.runAgentLoop` inserts a
      live placeholder assistant message and streams text into it, then finalizes with tool calls.
      Uses `stream:true` + `stream_options.include_usage` for token usage.
- [~] **Dedicated background terminal for the agent** + its own foreground-service notification
      [4.1.4: AiAgentService (foreground service) now shows an "AI agent · Working" notification while a
      turn runs (keeps it alive if the app is backgrounded) with a Stop action wired through AiAgentBus
      → AiViewModel.stop(). run_command still executes via ShellUtils.runUbuntu (sandbox) inside this
      turn. REMAINING: a *persistent* agent terminal the AI keeps across turns that the user can kill
      and the AI can restart + per-command "AI is running: <cmd>" text.]
      (mirror `com.rk.runner.RunService`): run `run_command` in a persistent agent session, notification
      shows "AI is running: <cmd>", user can Stop (kills it; the loop gets a "terminal killed" tool
      result), and the AI can start it again. Tie into `SessionService`/a new `AiTerminalService`.
- [x] **AI Settings screen** (4.1.5) — `com.rk.ai.AiSettingsScreen` (route `SettingsRoutes.Ai` →
      "ai_settings", in SettingsNavHost; entry in SettingsScreen list with drawables.bolt). Manages
      provider, per-provider API key (with Verify&save via listModels), custom base URL, default
      model, and "Reset tool permissions". Reads/writes AiPrefs directly.

### ⬜ Phase 3 — Polish
- Better Kiro-web-style chat styling + markdown rendering of assistant messages + code blocks with copy.
  [4.1.8: assistant replies now render fenced ``` code blocks as monospace cards with a Copy button
  (AiTab.AssistantContent); plain text otherwise. Full markdown (headings/lists/bold) still minimal.]
- More tools (search/grep, apply-patch/diff, delete, move) + per-tool descriptions.
  [4.1.6 + 4.1.7 done — full toolset listed above. 4.1.10: + fetch_url (web). REMAINING: multi-hunk apply-patch/diff.]
- Task/todo tools (4.1.9): set_tasks + complete_task (AiTaskStore) let the agent build a plan and tick
  it off; a "Tasks" panel in AiTab shows live progress. Auto-allowed (no permission prompt). System
  prompt tells the model to use them for multi-step work.
- Cancel/stop a running agent turn; retry; edit-and-resend.
  [4.1.3 + 4.1.8: stop() now also cancels the in-flight OkHttp call (AiClient.cancel()) and ignores
  the cancellation as an error. Retry/edit-resend still TODO.]
- MCP support (optional) so external tool servers can be attached.   [deferred / optional]

### Note on the "dedicated agent terminal"
run_command runs each command as an independent process in the Linux sandbox (ShellUtils.runUbuntu),
and AiAgentService shows a foreground notification updated with the current tool ("Read x", "Run: …")
with a **Stop** action that cancels the whole turn (= "kill the terminal"). There is no single
long-lived shell the AI keeps state in across commands; if that's wanted later, add an AiTerminal
that holds one persistent ubuntuProcess and pipes run_command through it.
- Cancel/stop a running agent turn; retry; edit-and-resend.   [stop DONE in 4.1.3 — AiViewModel.stop()
  cancels runJob + clears the permission gate; the send button becomes a Stop button while busy.
  (True in-flight network cancel of the OkHttp call is still TODO — currently the coroutine stops the
  loop and unblocks the UI; the in-flight stream is ignored.)]
- MCP support (optional) so external tool servers can be attached.

---

## 4. How to continue (for a future session)
1. Read this file. The engine is `com.rk.ai`; the entry point is `AiTab` (drawer, above Git).
2. For streaming: extend `AiClient` with an SSE method and have `AiViewModel.runAgentLoop` consume it,
   mutating the last assistant `AiMessage` in the session's `messages` list.
3. For the agent terminal: copy `RunService`'s foreground-service pattern; route `run_command` through it.
4. Build is verified via **GitHub Actions CI** (no local Android SDK in the dev env). Bump version in
   `app/build.gradle.kts` and add a `changelog-X.Y.Z.txt` at repo root for each release.
