# NovaBot 3D — Expressive 3D Robot AI Personal Assistant

NovaBot 3D is an interactive Android personal AI assistant featuring an expressive, stylized 3D robot companion that listens, speaks, thinks, and reacts with live 3D animations, emotional expressions, and interactive gestures while remembering user facts and tasks locally on the device.

## User Review & Critical Decisions

> [!IMPORTANT]
> The design and technical approach below incorporates your confirmed preferences from our initial Q&A:

- **Confirmed Decision 1 — 3D Avatar Visual Style**: **Expressive Stylized 3D Robot Character** rendered at 60 FPS on an interactive 3D projection canvas with dynamic lighting, floating hover physics, articulated head/ear/visor geometry, and emotional eye/face expressions (`Idle`, `Listening`, `Thinking`, `Speaking`, `Happy`, `Curious`, `Celebratory`, `Focused`).
- **Confirmed Decision 2 — Core Capabilities**: **Voice and Text Chat with Live 3D Reactions**, combining Android Speech-to-Text (`SpeechRecognizer`), Text-to-Speech (`TextToSpeech`) with lip/waveform synchronization, and **Firebase AI Logic (`gemini-3.8-flash`)** structured agent responses that simultaneously deliver conversational replies, trigger specific 3D robot emotions/animations, and automatically extract personal memories or action items.
- **Confirmed Decision 3 — Local Privacy & Storage**: **On-Device Local Storage Only without Sign-In** using **Room Database** so all conversation history, extracted personal memories, and quick assistant tasks stay strictly on the device without requiring any account login.

---

## 1. Overview & Core Concept

- **What It Does**: Provides a personal AI companion that users can talk to naturally via voice or text. As the user speaks or types, the 3D robot tilts its head, animates its glowing visor eyes, reacts with expressive 3D body language, speaks responses aloud with real-time audio waveform rings, and automatically saves useful personal context ("Memories") and reminders ("Tasks") to local storage.
- **Target Audience / Persona**: Users looking for an engaging, lifelike, privacy-first personal AI companion on Android that feels like an interactive 3D character rather than a static text chat window.
- **Key Value**: Transforms everyday AI assistance into a tactile, multimodal 3D experience where the assistant visibly emotes, responds to touch gestures (drag-to-rotate, tap-to-poke/greet), and retains context across sessions locally.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Interactive 3D Stage & Live Voice/Text Conversation (Main Screen)**:
   - The upper viewport showcases the **3D Robot Stage**, where NovaBot floats above a holographic concentric platform with ambient particle depth. Users can drag horizontally/vertically to orbit the 3D camera around the robot, tap the robot to trigger a playful wave/bounce reaction, or switch robot color skins & personalities.
   - A live **Emotion & Status HUD pill** displays the robot's current state (e.g., *"Listening..."*, *"Thinking deeply..."*, *"Excited"*), voice pitch/TTS toggle, and quick camera reset.
   - Below the 3D stage sits the **Conversational Stream & Smart Action Bar**: users can tap the pulsing **Push-to-Talk Microphone** button for voice input (with graceful runtime `RECORD_AUDIO` permission handling), type a prompt, or tap quick starter chips (*"Plan my day"*, *"Remember a note"*, *"Tell me something cool"*, *"Brainstorm ideas"*).
2. **Automatic Agent Memory & Task Extraction**:
   - When the user says something like *"Remind me to call Alex at 5 PM"* or *"Remember that I prefer oat milk lattes"*, the Gemini agent responds conversationally, triggers an affirmative 3D nod/happy animation, and automatically saves the memory or task card into the local Room database with an inline confirmation chip in the chat bubble.
3. **Agent Brain Drawer / Sheet (Memories, Tasks & Customization)**:
   - Accessible from the top bar, users can inspect and manage what NovaBot remembers about them (**Memory Bank**), check off or add **Assistant Tasks**, clear conversation history, or customize the 3D robot's chassis accent theme (Cyber Cyan, Solar Amber, Neon Emerald, Plasma Violet) and voice settings.

### Visual Identity & Theme
- **Aesthetic Direction**: Sleek Sci-Fi Companion Studio — deep atmospheric obsidian surfaces paired with luminous neon visor accents, tactile glassmorphic HUD cards, and smooth spring physics.
- **Color Palette & Mood**:
  - **Background / Stage Surface**: Deep Space Obsidian (`#090D16` to `#111827` radial stage gradient)
  - **Elevated HUD Cards**: Translucent Slate (`#1E293B` with subtle border highlights)
  - **Primary Accent (Nova Cyan)**: Electric Cyan (`#00E5FF`) for visor eyes, active voice rings, and primary controls
  - **Secondary Accent (Energy Violet)**: Vivid Indigo/Violet (`#7C4DFF`) for AI thinking states and memory badges
  - **Semantic Accents**: Emerald (`#10B981`) for task completion, Warm Amber (`#F59E0B`) for happy/curious expressions, Coral (`#EF4444`) for mic recording/errors
- **Typography & Hierarchy**:
  - **Display / Headers & HUD Labels**: `Space Grotesk` (bundled locally in `res/font/` via `font-util`) for crisp, futuristic geometric headlines and status pills.
  - **Body & Chat Transcript**: `Plus Jakarta Sans` (bundled locally in `res/font/`) for warm, highly legible conversational text.
  - **Telemetry & Timestamps**: `JetBrains Mono` for 3D coordinates, audio frequency readouts, and memory tags.
- **Interactive Feedback & Motion**:
  - Continuous 60 FPS procedural 3D animation loop: breathing hover bob, eye blinking, visor scanlines, ear antenna tilt, and reactive arm/body physics.
  - Spring-physics transitions (`spring()`) on chat bubbles, pulsing concentric voice rings when speaking or listening, and haptic feedback on robot touch interactions.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Custom 3D Projection & Shading Engine in Compose Canvas**
  - *Chosen Approach*: Build a dedicated, hardware-accelerated 3D transformation, perspective projection, Phong/cel-shaded lighting, and painter's algorithm z-sorting engine in Kotlin/Compose `Canvas` for the stylized 3D robot character.
  - *Why*: Guarantees zero external 3D asset-loading failures, instantaneous 60 FPS responsiveness to touch rotation and live AI emotion parameters, dynamic color skinning, and synchronized speech mouth/visor animations without fragile native `.glb` file dependencies.
  - *Alternatives Considered*: WebView Three.js or external native OpenGL wrappers (rejected due to sluggish startup, memory overhead, andCompose touch-interop lag).
- **Decision 2: Structured JSON Agent Output (`gemini-3.8-flash` via Firebase AI Logic)**
  - *Chosen Approach*: Configure `Firebase.ai.generativeModel("gemini-3.8-flash")` with `responseSchema` (`Schema.obj`) so every assistant turn returns structured JSON containing `replyText`, `emotion` (driving the 3D robot's facial expression and body posture), `gesture` (e.g., `NOD`, `WAVE`, `THINK_TILT`, `CELEBRATE`, `SCAN`), `extractedMemories` (auto-saved to Room), and `extractedTasks` (auto-saved to Room).
  - *Why*: Allows the AI agent to act as a true embodied 3D assistant—controlling its own 3D body language and local memory bank in a single low-latency inference call.
- **Decision 3: Local On-Device Persistence via Room Database**
  - *Chosen Approach*: Store all chat messages, personal user memories, and assistant tasks in an on-device Room SQLite database. Inject stored user memories into the Gemini system prompt context so the assistant genuinely remembers the user across sessions.
  - *Why*: Honors your explicit preference for private, sign-in-free local storage while giving the AI assistant persistent long-term memory.

---

## 4. Technical Architecture & Data Strategy

### Architecture & Component Diagram

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         MainActivity (Edge-to-Edge)                       │
│   • configureAppCheck(this, intent) [Before UI / ViewModel creation]      │
│   • Android SpeechRecognizer (Voice In) & TextToSpeech (Voice Out)        │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                      AssistantViewModel (MVVM State)                      │
│   • UI StateFlow: Robot3DState (Emotion, Gesture, Yaw/Pitch, Speaking)    │
│   • Chat & Agent Flow: Injects Local Memories -> Calls Gemini 3.8 Flash   │
│   • Auto-extracts new Memories & Tasks from structured AI JSON response   │
└───────────────┬───────────────────────────────────────────┬───────────────┘
                │                                           │
                ▼                                           ▼
┌───────────────────────────────────────┐   ┌───────────────────────────────┐
│     Firebase AI Logic (Gemini API)    │   │  AssistantRepository & Room   │
│   • Model: gemini-3.8-flash (by lazy) │   │   • ChatMessageEntity         │
│   • Schema.obj:                       │   │   • MemoryEntity (Context)    │
│     - replyText, emotion, gesture     │   │   • TaskEntity (Reminders)    │
│     - newMemories[], newTasks[]       │   │   • Reactive Flow<List<T>>    │
│   • 60s Coroutine Timeout & Error UI  │   └───────────────────────────────┘
└───────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI Layer                           │
│  ┌─────────────────────────────────┐  ┌────────────────────────────────┐  │
│  │   Interactive 3D Robot Stage    │  │   Agent Brain Sheet / Tabs     │  │
│  │ • 3D Mesh & Rig (Head, Visor,   │  │ • Memory Bank (Add/Delete)     │  │
│  │   Eyes, Ears, Torso, Floating   │  │ • Smart Tasks (Toggle/Create)  │  │
│  │   Hands, Energy Core, Pedestal) │  │ • 3D Robot Skin & Voice Config │  │
│  │ • Drag Orbit + Tap Poke Physics │  └────────────────────────────────┘  │
│  └─────────────────────────────────┘                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │               Conversational Stream & Voice/Text Bar                │  │
│  │ • Chat History Cards + Inline "Memory Saved" / "Task Added" Badges  │  │
│  │ • Quick Prompt Chips + Push-to-Talk Mic + Text Input + TTS Toggle   │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **`ChatMessageEntity` (Room Table `chat_messages`)**: `id`, `role` (`USER` or `ASSISTANT`), `content`, `emotionTag`, `gestureTag`, `attachedActionSummary`, `timestamp`.
- **`MemoryEntity` (Room Table `assistant_memories`)**: `id`, `fact` (e.g., *"Loves sci-fi movies"*), `category` (`PREFERENCE`, `PERSONAL`, `WORK`, `ROUTINE`), `createdAt`.
- **`TaskEntity` (Room Table `assistant_tasks`)**: `id`, `title`, `dueHint`, `isCompleted`, `priority` (`HIGH`, `NORMAL`), `createdAt`.
- **`Robot3DState` (In-Memory Reactive State)**: `emotion` (`NEUTRAL`, `HAPPY`, `LISTENING`, `THINKING`, `SPEAKING`, `CURIOUS`, `EXCITED`, `SLEEPY`), `activeGesture` (`NONE`, `WAVE`, `NOD`, `THINK_TILT`, `SCAN`, `CELEBRATE`), `cameraYaw`, `cameraPitch`, `cameraZoom`, `themeColor`, `isSpeaking`, `audioAmplitude`.

### Interactive Component & State Mapping
- **3D Robot Canvas Interactions**:
  - Horizontal/Vertical drag gestures update `cameraYaw` and `cameraPitch` with smooth damping; double-tap or clicking the "Reset View" pill springs the camera back to front-center.
  - Tapping directly on the 3D robot triggers an immediate tactile `WAVE` or `HAPPY` bounce gesture, haptic tick, and a friendly quip.
- **Voice & Text Input Pipeline**:
  - Tapping the Mic button checks `Manifest.permission.RECORD_AUDIO` via `rememberLauncherForActivityResult(RequestPermission())`. When granted, starts `SpeechRecognizer`, transitions the 3D robot to `LISTENING` posture (leaning forward, glowing cyan ears, audio ring), and populates the partial/final transcript.
  - Submitting text or voice transitions the robot to `THINKING` (tilted head, orbiting energy rings, animated visor dots) while querying `gemini-3.8-flash` with a 60-second timeout.
  - When the structured response arrives, the robot adopts the returned `emotion` and `gesture`, speaks `replyText` via `TextToSpeech` (if voice output is enabled) while animating its 3D visor waveform, and persists any newly extracted memories or tasks to Room.
- **Error Recovery**:
  - Network, App Check, or timeout failures transition the robot to an empathetic posture and display a clear, actionable error card with a one-tap **Retry** button.
