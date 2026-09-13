-- MiniChat E2EE v0.1
-- Run this in a FRESH Supabase project's SQL editor.

create table if not exists public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  handle text not null unique check (handle = lower(handle) and handle ~ '^[a-z0-9_]{3,24}$'),
  hpke_public_keyset text not null,
  signing_public_key text not null,
  updated_at timestamptz not null default now()
);

create table if not exists public.messages (
  id uuid primary key,
  sender_id uuid not null references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  kind text not null check (kind in ('text','file')),
  ciphertext_to_recipient text not null,
  ciphertext_to_sender text not null,
  signature text not null,
  file_path text,
  created_at timestamptz not null default now(),
  check (sender_id <> recipient_id),
  check ((kind = 'file' and file_path is not null) or (kind = 'text' and file_path is null))
);

create index if not exists messages_sender_recipient_created_idx
  on public.messages(sender_id, recipient_id, created_at);
create index if not exists messages_recipient_sender_created_idx
  on public.messages(recipient_id, sender_id, created_at);

alter table public.profiles enable row level security;
alter table public.messages enable row level security;

-- Public keys/handles are discoverable only to signed-in MiniChat users.
drop policy if exists "profiles readable by authenticated" on public.profiles;
create policy "profiles readable by authenticated"
on public.profiles for select
to authenticated
using (true);

drop policy if exists "users insert own profile" on public.profiles;
create policy "users insert own profile"
on public.profiles for insert
to authenticated
with check (auth.uid() = user_id);

drop policy if exists "users update own profile" on public.profiles;
create policy "users update own profile"
on public.profiles for update
to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

drop policy if exists "participants read messages" on public.messages;
create policy "participants read messages"
on public.messages for select
to authenticated
using (auth.uid() = sender_id or auth.uid() = recipient_id);

drop policy if exists "sender inserts messages" on public.messages;
create policy "sender inserts messages"
on public.messages for insert
to authenticated
with check (auth.uid() = sender_id and sender_id <> recipient_id);

insert into storage.buckets (id, name, public)
values ('chat-files', 'chat-files', false)
on conflict (id) do update set public = false;

-- Ciphertext uploads are allowed for signed-in users. Paths use random UUIDs and contain no original filename.
drop policy if exists "authenticated upload encrypted chat files" on storage.objects;
create policy "authenticated upload encrypted chat files"
on storage.objects for insert
to authenticated
with check (bucket_id = 'chat-files');

-- A ciphertext blob becomes downloadable only when a message row links it to the requester.
drop policy if exists "participants download encrypted chat files" on storage.objects;
create policy "participants download encrypted chat files"
on storage.objects for select
to authenticated
using (
  bucket_id = 'chat-files'
  and exists (
    select 1
    from public.messages m
    where m.file_path = storage.objects.name
      and (m.sender_id = auth.uid() or m.recipient_id = auth.uid())
  )
);

-- Optional cleanup of a sender's own uploaded object.
drop policy if exists "owners delete encrypted chat files" on storage.objects;
create policy "owners delete encrypted chat files"
on storage.objects for delete
to authenticated
using (bucket_id = 'chat-files' and owner_id = auth.uid()::text);
