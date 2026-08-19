-- myChat table in the shared "generic" Supabase project.
-- Convention: one table prefix per app (mychat_, grouptrack_, …) so small
-- projects can share this database without colliding.
-- Run once in the SQL editor. The myChat server uses the service role key,
-- which bypasses RLS; leave RLS on so the anon key cannot read this table.

create table if not exists public.mychat_device_accounts (
  mobile text primary key,
  ssaid text not null,
  firebase_uid text,
  display_name text,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);

alter table public.mychat_device_accounts
  add column if not exists firebase_uid text;

alter table public.mychat_device_accounts
  add column if not exists display_name text;

alter table public.mychat_device_accounts
  add column if not exists status text;

alter table public.mychat_device_accounts
  add column if not exists is_admin boolean not null default false;

alter table public.mychat_device_accounts enable row level security;
