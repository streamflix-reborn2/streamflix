# Supabase cloud sync

For the complete user-facing setup guide, see [`supabase_installation.md`](../supabase_installation.md) or open **Settings → Account & sync → Instructions** in Streamflix.

Create a Supabase project, apply the migrations in `supabase/migrations/`, and use the project URL and **publishable** key in Streamflix under **Settings → Account & sync**.

Do not use a `service_role` or other secret key in the app. The migration enables RLS and limits each authenticated user to their own `user_media_state` rows.

After configuring the connection, users can create/sign into their own Supabase Auth account. Account and sync actions remain disabled until both connection fields are valid.
