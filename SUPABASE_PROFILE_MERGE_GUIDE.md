# Supabase integration after merging local profiles

This document is for maintainers who merge the multi-profile branch into the
main branch. It describes how to keep the current user-configured Supabase
connection while allowing every local Streamflix profile to sign in to a
different Supabase Auth account.

## Intended result

The Supabase project connection remains global:

- one Supabase project URL for the whole app installation;
- one publishable/anon key for the whole app installation;
- the existing manual URL/key controls remain in **Settings → Account & sync**.

Authentication and synchronized data become profile-specific:

- every local profile has its own persisted Supabase Auth session;
- every local profile can use a different email/password account;
- pending mutations, workers, Realtime events, account metadata, databases, and
  caches are associated with a stable local `profileId`;
- switching profiles never applies one profile's cloud rows to another
  profile's local database.

No additional Supabase project is required for each profile. The existing RLS
policies isolate rows by `auth.uid() = user_id`, so separate Supabase Auth
accounts can safely share the same project URL, publishable key, and
`user_media_state` table.

## Important distinction

Do not store a Supabase URL or key per profile unless the product explicitly
decides to support separate Supabase projects. For the expected design:

```text
App installation
├── Shared Supabase URL + publishable key
├── Local profile "Default" ── Supabase account A
├── Local profile "Alex" ───── Supabase account B
└── Local profile "Kids" ───── Supabase account C
```

The local profile ID is a device-local namespace. The Supabase user ID is the
cloud identity. They must not be treated as interchangeable.

## Recommended merge order

1. Merge and verify profile-scoped databases, preferences, and user-data
   caches first.
2. Make the Supabase client and persisted Auth session profile-scoped.
3. Scope account metadata and the pending mutation queue.
4. Pass `profileId` explicitly through the sync manager, worker, scheduler, and
   Realtime layer.
5. Connect profile switch/deletion lifecycle callbacks.
6. Update the settings screen to display the active profile's cloud account.
7. Add legacy migration and cross-profile regression tests.

Do not enable account controls for multiple profiles while the app still uses
one global `SupabaseClient`. The last profile to authenticate would replace the
session used by every other profile.

## 1. Keep manual connection configuration global

The following behavior in `SupabaseProvider` must remain:

- read the URL and publishable key from `supabase_connection`;
- validate and normalize the URL;
- retain `saveConfig()`, `clearConfig()`, `getUrl()`, and `getPublicKey()`;
- use a connection fingerprint so sessions from one Supabase project cannot be
  restored against another project.

Only client/session storage should become profile-scoped. Replace the single
`clientInstance` with a map:

```kotlin
private data class ProfileClient(
    val fingerprint: String,
    val client: SupabaseClient,
)

private val clients = mutableMapOf<String, ProfileClient>()
```

Expose explicit accessors:

```kotlin
suspend fun clientFor(context: Context, profileId: String): SupabaseClient
fun clientOrNull(profileId: String): SupabaseClient?
suspend fun removeProfile(profileId: String)
```

Create each client with the same configured URL/key but a different Auth
session key:

```kotlin
private fun sessionKey(profileId: String, fingerprint: String): String =
    "streamflix_supabase_session-${fingerprint.hashCode()}-$profileId"
```

The session key must contain both the configuration fingerprint and the stable
profile ID. Using only the profile ID can restore a session created for a
different Supabase project after the user changes the manual URL.

When the URL/key changes or is cleared:

- stop Realtime;
- close and remove every cached profile client;
- clear or isolate sessions belonging to the old fingerprint;
- reinitialize only the active profile after the new configuration is saved.

### Preserve the existing default-profile session

The current single-profile session key is:

```text
streamflix_supabase_session-<configuration fingerprint hash>
```

Avoid unexpectedly signing out existing users during the profiles migration.
For the first profile migration, either:

- let the `default` profile continue using the legacy key and use suffixed keys
  for all other profiles; or
- explicitly migrate the persisted default session before removing the legacy
  key.

The first option is simpler:

```kotlin
private fun sessionKey(profileId: String, fingerprint: String): String {
    val legacy = "streamflix_supabase_session-${fingerprint.hashCode()}"
    return if (profileId == "default") legacy else "$legacy-$profileId"
}
```

Profile IDs must be stable and safe for preference/session keys. Renaming a
profile must not change its ID.

## 2. Scope cloud-account metadata

`CloudAccountStore` currently stores global keys such as `active_user_id` and
`legacy_owner_id`. Change them to profile-scoped keys:

```text
active_user_id_<profileId>
active_user_email_<profileId>
legacy_owner_id_<profileId>
```

Recommended API:

```kotlin
fun activeUserId(context: Context, profileId: String): String?
fun activeUserEmail(context: Context, profileId: String): String?
fun setActiveAccount(
    context: Context,
    profileId: String,
    userId: String?,
    email: String?,
)
fun legacyOwnerId(context: Context, profileId: String): String?
fun claimLegacyData(context: Context, profileId: String, userId: String)
fun clearProfile(context: Context, profileId: String)
```

Migrate the old unsuffixed keys to the `default` profile once. Do not copy them
to every profile.

It is recommended to prevent the same Supabase account from being linked to
two local profiles on one installation. Add a reverse lookup such as
`profileIdForUser(context, userId)` and reject sign-in when the user ID is
already owned by another profile. Otherwise two profiles intentionally or
accidentally mirror the same cloud library.

If maintainers choose to allow that behavior, it must be a documented product
decision and all workers and local storage must still remain profile-scoped.

## 3. Scope the pending mutation queue

`CloudMutationStore` must not use one global `pending_media_states` value.
Store a separate serialized queue per profile:

```text
pending_media_states_<profileId>
```

Every operation needs `profileId`:

```kotlin
fun enqueue(context: Context, profileId: String, state: RemoteMediaState)
fun pendingForUser(
    context: Context,
    profileId: String,
    userId: String,
): List<RemoteMediaState>
fun acknowledge(
    context: Context,
    profileId: String,
    uploaded: List<RemoteMediaState>,
)
fun clearProfile(context: Context, profileId: String)
```

Move the old global queue to the `default` profile once. Never merge it into
new profiles.

Keep the current version-aware `acknowledge()` behavior. It must remove the
uploaded/stale version without deleting a newer mutation queued during the
upload.

## 4. Make `CloudSyncManager` profile-explicit

Do not let long-running synchronization repeatedly read
`ProfileManager.activeProfileId`. Capture the profile ID at the start and pass
it through every operation.

At minimum, these operations need a `profileId`:

- initialization;
- account activation;
- local-state collection;
- pending upload;
- remote fetch/application;
- manual sync;
- Realtime application;
- sign-in, sign-up, and sign-out;
- local database/cache lookup.

Use the client belonging to that profile:

```kotlin
val client = SupabaseProvider.clientFor(appContext, profileId)
client.auth.awaitInitialization()
```

Do not use a global `SupabaseProvider.client` inside a profile-scoped sync.
Pass the captured `SupabaseClient` into `fetchRemote()` and `upsert()`.

The sync operation should verify:

1. the expected local profile still exists;
2. the profile's current session user ID matches the expected user ID;
3. the active profile has not changed before applying rows to active UI/cache
   state.

All `AppDatabase` access must include the target profile:

```kotlin
AppDatabase.getInstanceForProvider(provider.name, context, profileId)
AppDatabase.databaseNameFor(provider.name, profileId)
```

All `UserDataCache` reads/writes/clears must also include `profileId`.

Keep the synchronization conflict fixes already present on this branch:

- fetch remote rows before flushing an offline queue;
- compare actual media timestamps before `client_updated_at_millis`;
- do not allow Realtime to overwrite a newer pending mutation;
- skip identical or stale remote rows;
- apply a provider's remote rows transactionally;
- do not silently clear local data when an account ownership conflict exists.

### Initialization behavior

On app startup, initialize only the active profile. Wait for Auth session
restoration before deciding whether the profile is signed out.

If no valid session is restored:

- stop Realtime for that profile;
- preserve its local library;
- preserve its ownership marker;
- do not interpret a missing/expired session as permission to erase data.

### Account switching behavior

Before linking a different Supabase user to a profile, inspect its existing
local state and ownership marker. If the state belongs to another account,
preserve it and show a conflict error. Never silently upload it to the new
account or replace it with the new account's cloud rows.

The maintainer should provide an explicit resolution flow if switching an
occupied profile to another cloud account is desired, for example:

- cancel and keep the existing profile/account;
- create a new local profile for the other account;
- explicitly clear the current profile's local user state and relink.

## 5. Make sync hooks profile-aware

`CloudSyncHooks` should capture the active profile when a local mutation occurs:

```kotlin
val profileId = ProfileManager.activeProfileId ?: return
val userId = CloudSyncManager.currentUserId()
    ?: CloudAccountStore.activeUserId(appContext, profileId)
    ?: return
```

Then enqueue and schedule using both identities:

```kotlin
CloudMutationStore.enqueue(appContext, profileId, state)
CloudSyncScheduler.enqueue(appContext, profileId, userId)
```

The stored account fallback is useful while the profile's Supabase client is
being recreated, but it must only read the account stored for the same profile.

Make sure playback pause/exit, watched/unwatched actions, favorites, and
TV-show `isWatching` changes still call these hooks after the profiles merge.
Profile work often changes cache/database APIs and can accidentally drop the
hook calls.

## 6. Scope WorkManager jobs

Include `profileId` and the expected Supabase `userId` in worker input data.
Use a unique work name and tag containing the profile ID:

```text
cloud-user-state-<profileId>-<userId>
cloud-profile-<profileId>
```

The worker must:

1. initialize the client for its input `profileId`;
2. wait for Auth initialization;
3. confirm the restored session matches the expected user ID;
4. avoid applying remote state if another profile is active;
5. leave pending mutations queued if the session is absent or changed.

On profile deletion, cancel all work tagged for that profile.

The safest initial implementation only runs a profile's worker while that
profile is active. Its pending queue can be resumed the next time the user
switches to it. Background synchronization for inactive profiles is possible,
but it requires carefully avoiding active UI/cache notifications and opening
the inactive profile's database explicitly.

## 7. Scope Realtime

`CloudRealtimeSync` should track:

- active local profile ID;
- active Supabase user ID;
- the exact `SupabaseClient` that created the channel;
- channel and collector job.

The channel name should include both identities:

```text
user-media-state-<profileId>-<userId>
```

Continue filtering Postgres changes by `user_id`. Before applying an event,
verify that:

- the event belongs to the expected Supabase user;
- the local profile that opened the channel is still active;
- the event does not lose to a newer pending local mutation.

Remove the channel through the same client that created it. A channel created
by profile A must never be removed through profile B's client.

On every profile switch, stop the old channel before starting the new one.

## 8. Connect profile lifecycle events

The profile manager needs explicit cloud callbacks.

After switching profiles:

1. reset/select the profile-scoped Room database;
2. switch the profile-scoped preferences and user-data cache;
3. notify screens that the provider/profile context changed;
4. stop the previous Realtime channel;
5. initialize the selected profile's Supabase session;
6. synchronize and start Realtime only if that profile is authenticated.

A useful entry point is:

```kotlin
fun CloudSyncManager.onProfileChanged(context: Context, profileId: String)
```

On profile deletion:

```kotlin
suspend fun CloudSyncManager.onProfileDeleted(
    context: Context,
    profileId: String,
)
```

It must:

- stop Realtime if it belongs to the deleted profile;
- cancel that profile's workers;
- clear that profile's mutation queue;
- clear its cloud-account metadata;
- close its Supabase client;
- delete its persisted Auth session;
- leave every other profile's session and queue untouched.

Clean up orphaned profile-scoped account entries during profile-manager
initialization in case a profile was removed before cloud cleanup completed.

## 9. Make account settings reflect the active profile

Keep the existing manual Supabase URL/key settings global. Only the account
status and actions are profile-specific.

The Account & sync section should show:

- the active local profile name;
- that profile's signed-in email, or “Signed out”;
- Sign in/Sign up only for the active profile when signed out;
- Sign out/Sync now only for the active profile when signed in.

When binding the settings screen, initialize and await the active profile's Auth
session before the final refresh. Otherwise the UI may briefly show “Signed
out” while the stored session is still loading.

If the active profile changes while an authentication or manual sync dialog is
open, cancel or reject the result. Do not finish signing profile A into the
client/database for profile B.

Provide a clear error when the entered Supabase account is already linked to
another local profile, preferably including that profile's name.

## 10. Database and cache migration

The profiles branch should migrate the existing single-profile database,
preferences, cache, cloud account, Auth session, and pending queue to a stable
`default` profile.

Migration requirements:

- run once and be idempotent;
- never copy existing user state into every new profile;
- do not delete legacy data until the new profile-scoped location exists;
- preserve the current selected provider for the default profile;
- retain the current manual Supabase URL and publishable key globally;
- retain the existing default profile's signed-in session where possible.

Profile-specific database names should include both profile and provider:

```text
<sanitized-profile-id>_<sanitized-provider-name>.db
```

Do not use a profile's editable display name in file names or preference keys.

## 11. Supabase schema and RLS

No schema change is required merely to add local profiles. The current primary
key and RLS design already use the authenticated Supabase user:

```text
user_id + provider + media_type + media_id
```

Keep all policies restricted to:

```sql
auth.uid() = user_id
```

Never add a client-provided local `profile_id` to an RLS policy as a substitute
for `auth.uid()`. A local profile ID is not a trusted cloud identity and may be
different on another device.

For the same person's profile to synchronize across two devices, both local
profiles simply sign in to the same Supabase Auth account. Their local profile
IDs do not need to match.

## 12. Required regression tests

At minimum, test the following before merging:

### Migration

- Existing URL/key remain configured.
- Existing signed-in session is restored for the default profile.
- Existing cloud account/owner keys move only to the default profile.
- Existing pending mutations move only to the default profile.
- Existing databases and caches remain accessible from the default profile.

### Isolation

- Profile A and B can sign in to different accounts in the same Supabase
  project.
- Switching A → B changes the displayed email and local database.
- A Realtime event cannot modify B's database after a switch.
- A worker queued for A cannot apply rows while B is active.
- Deleting A does not delete B's session, queue, account metadata, or data.
- Changing the global Supabase URL does not restore sessions from the old
  project.

### Synchronization

- Offline progress from A never enters B's queue.
- A stale offline mutation cannot overwrite newer remote progress.
- A newer local unwatched state can clear an older remote completion.
- Duplicate Realtime events do not repeatedly invalidate Room observers.
- Playback pause/exit queues movie, episode, and TV-show state.
- Manual Sync now affects only the active profile.

### Account safety

- The same cloud account cannot be linked to two profiles when uniqueness is
  enforced.
- A different account cannot silently replace local data owned by the previous
  account.
- A missing or expired session does not clear local data.

## Maintainer completion checklist

- [ ] Global manual URL/key controls still work.
- [ ] `SupabaseProvider` has one client/session per profile and configuration.
- [ ] Default-profile legacy session is preserved or deliberately migrated.
- [ ] Account metadata and pending queues are profile-scoped.
- [ ] All sync database/cache calls receive `profileId`.
- [ ] Workers carry and validate profile/user identity.
- [ ] Realtime tracks and validates profile/user/client identity.
- [ ] Profile switch and deletion invoke cloud lifecycle cleanup.
- [ ] Settings show the active profile's account.
- [ ] Cross-account conflicts preserve local data.
- [ ] RLS still uses only the authenticated Supabase user ID.
- [ ] Migration and isolation tests pass on mobile and TV builds.

