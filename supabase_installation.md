# Streamflix account sync: Supabase installation

Streamflix can synchronize favorites and watch history between devices through a Supabase project that you create and control. The app does not use a shared Streamflix Supabase account: each person can connect the app to their own project and create their own Supabase Auth account.

This guide is also available from **Settings → Account & sync → Instructions** inside the app.

Maintainers planning to merge multi-profile support should also read
[`SUPABASE_PROFILE_MERGE_GUIDE.md`](SUPABASE_PROFILE_MERGE_GUIDE.md). The
Supabase project connection remains global, while Auth sessions and sync state
must become profile-scoped.

## What you need

- A free Supabase account: [supabase.com](https://supabase.com/).
- A Supabase project.
- The project URL.
- The project's publishable key. Older projects may call this the `anon` key.
- The setup SQL from this guide or the **Copy database setup SQL** button in the app.

The free Supabase plan is normally sufficient for a personal account or a small group of friends and family. Supabase controls its plan limits separately from Streamflix.

## 1. Create a Supabase project

1. Open the [Supabase dashboard](https://supabase.com/dashboard).
2. Create a new project, or select an existing project dedicated to Streamflix.
3. Choose a strong database password and keep it private. Streamflix does not need the database password.
4. Wait until the project has finished provisioning.

For a shared family project, everyone may use the same project URL and publishable key, but each person must use a different email/password account in Supabase Auth. The security policies below keep each account's media rows separate.

## 2. Apply the database setup SQL

Open **SQL Editor** in the Supabase dashboard and create a new query. Then either:

- copy the SQL from `supabase/migrations/` in the repository, or
- open Streamflix's **Copy database setup SQL** option and paste the copied SQL.

Run the complete script once. It creates the `public.user_media_state` table, indexes, row-level security policies, the timestamp trigger, Data API grants, and the Realtime publication entry.

The table stores the synchronization state for movies, TV shows, and episodes. It does not store passwords or the Supabase database password.

## 3. Verify the database setup

In the Supabase dashboard, open **Table Editor** and confirm that `user_media_state` exists. Then open **Authentication → Policies** or the table's policies view and confirm that policies exist for:

- `SELECT`
- `INSERT`
- `UPDATE`
- `DELETE`

Every policy must restrict rows to the signed-in user's ID with `auth.uid() = user_id`. Do not remove or weaken these policies.

The script also grants the `authenticated` role access to the table. This is required in addition to RLS because Supabase's Data API access and row-level access are separate controls.

## 4. Enable email authentication

Supabase Auth uses email and password for Streamflix accounts.

1. Open **Authentication → Providers** in the Supabase dashboard.
2. Ensure the **Email** provider is enabled.
3. Decide whether email confirmation should be required.

If email confirmation is enabled, creating an account may show a confirmation-required message. Confirm the email first, then return to Streamflix and sign in. If confirmation is disabled for a private project, the account can usually sign in immediately.

## 5. Enter the project connection in Streamflix

In Streamflix, open **Settings → Account & sync**.

1. Paste the project URL. It normally looks like:

   `https://your-project-ref.supabase.co`

2. Paste the **publishable** key. On older Supabase projects, use the legacy **anon** key.
3. Return to the Account & sync section and create or sign in to a Supabase Auth account.
4. Use **Sync now** if you want to start synchronization immediately.

The account controls remain unavailable until both connection fields are configured. Clearing the project URL also removes the saved publishable key. This does not delete or clear the local Streamflix database.

## Keys and security

Use only the publishable key (or legacy anon key) in Streamflix. These keys are designed for use in client applications and are protected by RLS policies.

Never paste any of the following into Streamflix or publish them:

- `service_role` keys
- secret keys
- the database password
- dashboard access tokens

The publishable/anon key is not a replacement for RLS. The policies in the setup script are what prevent one authenticated user from reading or modifying another user's rows.

## How synchronization works

Streamflix keeps its normal local database and uses Supabase as an additional synchronization store:

- local favorites and watch history remain available without a connection;
- signing in merges the local state with the account's cloud state;
- changes are uploaded after local changes and can also be synchronized manually;
- Realtime notifications help other devices receive changes quickly;
- signing out stops synchronization but does not clear local data;
- changing to a different signed-in account is blocked when the existing local state belongs to another account, preventing that data from being silently overwritten or uploaded to the wrong account.

## Troubleshooting

### “Configure Supabase before using account sync”

Check that both fields are filled in and that the URL uses `https://`. The key must be the publishable/anon key for the same project as the URL.

### Sign-up succeeds but Streamflix asks me to sign in

Email confirmation is probably enabled. Confirm the message sent by Supabase, then sign in again.

### Sync fails with a table or permission error

Run the complete setup SQL again and verify that:

1. `user_media_state` exists in the `public` schema;
2. the four RLS policies exist;
3. the `authenticated` role has `SELECT`, `INSERT`, `UPDATE`, and `DELETE` grants;
4. the app is using the URL and key from the same Supabase project.

### Realtime updates are delayed

The table must be included in the `supabase_realtime` publication. The setup SQL adds it automatically when possible. You can verify this under **Database → Publications** in the Supabase dashboard.

### I removed the URL by mistake

Enter the URL and publishable key again. Local favorites and watch history are not deleted when the connection is removed.

## Updating the setup

Keep the SQL and migration files from the Streamflix repository with your project notes. If a future Streamflix release changes the cloud schema, apply the new migration described by that release. Do not manually remove existing RLS policies unless the new migration replaces them.
