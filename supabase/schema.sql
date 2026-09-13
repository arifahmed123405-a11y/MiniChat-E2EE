-- MiniChat E2EE v1.0 backend, isolated inside the shared EarnWall Supabase project.
-- All runtime tables and storage objects are prefixed with minichat_.

create table if not exists public.minichat_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  handle text not null unique check (handle = lower(handle) and handle ~ '^[a-z0-9_]{3,24}$'),
  hpke_public_keyset text not null,
  signing_public_key text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.minichat_profiles
  add column if not exists created_at timestamptz not null default now();

create table if not exists public.minichat_messages (
  id uuid primary key,
  sender_id uuid not null references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('text','file')),
  ciphertext_to_recipient text not null,
  ciphertext_to_sender text not null,
  signature text not null,
  file_path text,
  delivered_at timestamptz,
  read_at timestamptz,
  created_at timestamptz not null default now(),
  check (sender_id <> recipient_id),
  check ((kind = 'file' and file_path is not null) or (kind = 'text' and file_path is null))
);

alter table public.minichat_messages
  add column if not exists delivered_at timestamptz,
  add column if not exists read_at timestamptz;

create table if not exists public.minichat_blocks (
  owner_id uuid not null references auth.users(id) on delete cascade,
  blocked_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (owner_id, blocked_id),
  check (owner_id <> blocked_id)
);

create index if not exists minichat_messages_sender_recipient_created_idx
  on public.minichat_messages(sender_id, recipient_id, created_at desc);
create index if not exists minichat_messages_recipient_sender_created_idx
  on public.minichat_messages(recipient_id, sender_id, created_at desc);
create index if not exists minichat_messages_recipient_unread_idx
  on public.minichat_messages(recipient_id, created_at desc)
  where read_at is null;

alter table public.minichat_profiles enable row level security;
alter table public.minichat_messages enable row level security;
alter table public.minichat_blocks enable row level security;

revoke all on table public.minichat_profiles from anon;
revoke all on table public.minichat_messages from anon;
revoke all on table public.minichat_blocks from anon;

revoke all on table public.minichat_profiles from authenticated;
revoke all on table public.minichat_messages from authenticated;
revoke all on table public.minichat_blocks from authenticated;

grant select, insert, update on table public.minichat_profiles to authenticated;
grant select on table public.minichat_messages to authenticated;
grant insert (id, sender_id, recipient_id, kind, ciphertext_to_recipient, ciphertext_to_sender, signature, file_path)
  on table public.minichat_messages to authenticated;
grant update (delivered_at, read_at) on table public.minichat_messages to authenticated;
grant select, insert, delete on table public.minichat_blocks to authenticated;

drop policy if exists "minichat profiles readable by authenticated" on public.minichat_profiles;
create policy "minichat profiles readable by authenticated"
on public.minichat_profiles for select to authenticated using (true);

drop policy if exists "minichat users insert own profile" on public.minichat_profiles;
create policy "minichat users insert own profile"
on public.minichat_profiles for insert to authenticated
with check ((select auth.uid()) = user_id);

drop policy if exists "minichat users update own profile" on public.minichat_profiles;
create policy "minichat users update own profile"
on public.minichat_profiles for update to authenticated
using ((select auth.uid()) = user_id)
with check ((select auth.uid()) = user_id);

drop policy if exists "minichat participants read messages" on public.minichat_messages;
create policy "minichat participants read messages"
on public.minichat_messages for select to authenticated
using ((select auth.uid()) = sender_id or (select auth.uid()) = recipient_id);

drop policy if exists "minichat sender inserts messages" on public.minichat_messages;
create policy "minichat sender inserts messages"
on public.minichat_messages for insert to authenticated
with check ((select auth.uid()) = sender_id and sender_id <> recipient_id);

drop policy if exists "minichat recipient updates delivery state" on public.minichat_messages;
create policy "minichat recipient updates delivery state"
on public.minichat_messages for update to authenticated
using ((select auth.uid()) = recipient_id)
with check ((select auth.uid()) = recipient_id);

drop policy if exists "minichat owner reads blocks" on public.minichat_blocks;
create policy "minichat owner reads blocks"
on public.minichat_blocks for select to authenticated
using ((select auth.uid()) = owner_id);

drop policy if exists "minichat owner creates blocks" on public.minichat_blocks;
create policy "minichat owner creates blocks"
on public.minichat_blocks for insert to authenticated
with check ((select auth.uid()) = owner_id);

drop policy if exists "minichat owner removes blocks" on public.minichat_blocks;
create policy "minichat owner removes blocks"
on public.minichat_blocks for delete to authenticated
using ((select auth.uid()) = owner_id);

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('minichat-files', 'minichat-files', false, 31457280, array['application/octet-stream']::text[])
on conflict (id) do update
set public = false,
    file_size_limit = 31457280,
    allowed_mime_types = array['application/octet-stream']::text[];

drop policy if exists "minichat upload encrypted files" on storage.objects;
create policy "minichat upload encrypted files"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'minichat-files'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);

drop policy if exists "minichat read encrypted files" on storage.objects;
create policy "minichat read encrypted files"
on storage.objects for select to authenticated
using (
  bucket_id = 'minichat-files'
  and (
    (storage.foldername(name))[1] = (select auth.uid())::text
    or exists (
      select 1 from public.minichat_messages m
      where m.file_path = storage.objects.name
        and ((select auth.uid()) = m.sender_id or (select auth.uid()) = m.recipient_id)
    )
  )
);

drop policy if exists "minichat owners delete encrypted files" on storage.objects;
create policy "minichat owners delete encrypted files"
on storage.objects for delete to authenticated
using (
  bucket_id = 'minichat-files'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);
