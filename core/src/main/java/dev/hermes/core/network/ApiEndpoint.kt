package dev.hermes.core.network

import io.ktor.http.HttpMethod

sealed class ApiEndpoint(val path: String, val method: HttpMethod) {
  data object Health : ApiEndpoint("/health", HttpMethod.Get)

  data object AuthStatus : ApiEndpoint("/api/auth/status", HttpMethod.Get)
  data object AuthLogin : ApiEndpoint("/api/auth/login", HttpMethod.Post)
  data object AuthLogout : ApiEndpoint("/api/auth/logout", HttpMethod.Post)

  data object Sessions : ApiEndpoint("/api/sessions", HttpMethod.Get)
  data object SessionDetail : ApiEndpoint("/api/session", HttpMethod.Get)
  data object SessionStatus : ApiEndpoint("/api/session/status", HttpMethod.Get)
  data object SessionNew : ApiEndpoint("/api/session/new", HttpMethod.Post)
  data object SessionRename : ApiEndpoint("/api/session/rename", HttpMethod.Post)
  data object SessionDelete : ApiEndpoint("/api/session/delete", HttpMethod.Post)
  data object SessionPin : ApiEndpoint("/api/session/pin", HttpMethod.Post)
  data object SessionArchive : ApiEndpoint("/api/session/archive", HttpMethod.Post)
  data object SessionMove : ApiEndpoint("/api/session/move", HttpMethod.Post)

  data object ChatStream : ApiEndpoint("/api/chat/stream", HttpMethod.Get)
  data object ChatStreamStatus : ApiEndpoint("/api/chat/stream/status", HttpMethod.Get)
  data object ChatCancel : ApiEndpoint("/api/chat/cancel", HttpMethod.Get)
  data object ChatStart : ApiEndpoint("/api/chat/start", HttpMethod.Post)
  data object ChatSteer : ApiEndpoint("/api/chat/steer", HttpMethod.Post)

  data object Projects : ApiEndpoint("/api/projects", HttpMethod.Get)
  data object Workspaces : ApiEndpoint("/api/workspaces", HttpMethod.Get)
  data object WorkspaceSuggest : ApiEndpoint("/api/workspaces/suggest", HttpMethod.Get)
  data object WorkspaceList : ApiEndpoint("/api/list", HttpMethod.Get)
  data object File : ApiEndpoint("/api/file", HttpMethod.Get)
  data object FileRaw : ApiEndpoint("/api/file/raw", HttpMethod.Get)
  data object Models : ApiEndpoint("/api/models", HttpMethod.Get)
  data object Providers : ApiEndpoint("/api/providers", HttpMethod.Get)
  data object Settings : ApiEndpoint("/api/settings", HttpMethod.Get)
  data object Reasoning : ApiEndpoint("/api/reasoning", HttpMethod.Get)
  data object Profiles : ApiEndpoint("/api/profiles", HttpMethod.Get)
  data object Personalities : ApiEndpoint("/api/personalities", HttpMethod.Get)
  data object Commands : ApiEndpoint("/api/commands", HttpMethod.Get)
  data object Crons : ApiEndpoint("/api/crons", HttpMethod.Get)
  data object CronsStatus : ApiEndpoint("/api/crons/status", HttpMethod.Get)
  data object CronsOutput : ApiEndpoint("/api/crons/output", HttpMethod.Get)
  data object Skills : ApiEndpoint("/api/skills", HttpMethod.Get)
  data object SkillsContent : ApiEndpoint("/api/skills/content", HttpMethod.Get)
  data object Memory : ApiEndpoint("/api/memory", HttpMethod.Get)
  data object Usage : ApiEndpoint("/api/usage", HttpMethod.Get)

  data object Upload : ApiEndpoint("/api/upload", HttpMethod.Post)
  data object Btw : ApiEndpoint("/api/btw", HttpMethod.Post)
  data object Background : ApiEndpoint("/api/background", HttpMethod.Post)
  data object BackgroundStatus : ApiEndpoint("/api/background/status", HttpMethod.Get)

  data object DefaultModel : ApiEndpoint("/api/default-model", HttpMethod.Post)
  data object ReasoningSet : ApiEndpoint("/api/reasoning", HttpMethod.Post)
  data object ProfileSwitch : ApiEndpoint("/api/profile/switch", HttpMethod.Post)
  data object PersonalitySet : ApiEndpoint("/api/personality/set", HttpMethod.Post)

  data object SessionBranch : ApiEndpoint("/api/session/branch", HttpMethod.Post)
  data object SessionTruncate : ApiEndpoint("/api/session/truncate", HttpMethod.Post)
  data object SessionUpdate : ApiEndpoint("/api/session/update", HttpMethod.Post)
  data object SessionCompress : ApiEndpoint("/api/session/compress", HttpMethod.Post)
  data object SessionUndo : ApiEndpoint("/api/session/undo", HttpMethod.Post)
  data object SessionRetry : ApiEndpoint("/api/session/retry", HttpMethod.Post)
}