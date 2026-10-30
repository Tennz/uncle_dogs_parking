-- Uncle Dogs Parking v1.8.3
-- Run once in the EXISTING Supabase project's SQL Editor after the app has initialized
-- udp_reservations and udp_entry_intents. No new Supabase project is needed.
-- First enable Supabase Cron / pg_cron in Dashboard -> Integrations -> Cron if it is disabled.
-- This scheduled task expires unused reservations 90 minutes after reserved_at and frees
-- their slots when the application next reads slot state. It also expires temporary entry intents.

CREATE OR REPLACE FUNCTION public.udp_expire_due_reservations()
RETURNS TABLE(expired_reservations integer, expired_entry_intents integer)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $function$
DECLARE
    reservation_rows integer := 0;
    intent_rows integer := 0;
BEGIN
    UPDATE public.udp_reservations
       SET status = 'EXPIRED'
     WHERE status = 'RESERVED'
       AND reserved_at + interval '90 minutes' <= now();
    GET DIAGNOSTICS reservation_rows = ROW_COUNT;

    UPDATE public.udp_entry_intents
       SET status = 'EXPIRED'
     WHERE status = 'PREPARED'
       AND expires_at <= now();
    GET DIAGNOSTICS intent_rows = ROW_COUNT;

    RETURN QUERY SELECT reservation_rows, intent_rows;
END;
$function$;

-- Prevent normal application roles from invoking this maintenance function directly.
REVOKE ALL ON FUNCTION public.udp_expire_due_reservations() FROM PUBLIC;

-- Supabase replaces the existing job when the same job name is scheduled again.
SELECT cron.schedule(
    'udp-reservation-expiry-v1',
    '* * * * *',
    'SELECT public.udp_expire_due_reservations();'
);

-- Verify the scheduled job after running this file:
-- SELECT jobid, jobname, schedule, active FROM cron.job WHERE jobname = 'udp-reservation-expiry-v1';
-- Check latest executions:
-- SELECT jobid, status, start_time, end_time, return_message FROM cron.job_run_details
-- WHERE jobid = (SELECT jobid FROM cron.job WHERE jobname = 'udp-reservation-expiry-v1')
-- ORDER BY start_time DESC LIMIT 10;
