do $$
begin
    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'user_media_state'
    ) then
        alter publication supabase_realtime
            add table public.user_media_state;
    end if;
end;
$$;
