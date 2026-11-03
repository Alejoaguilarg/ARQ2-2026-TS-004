-- The service logs in as its own role, ${appUser}: not a superuser, not the owner, and with no privileges
-- of its own beyond those it inherits from audit_rw (INSERT and SELECT on the trail, the partition function).
--
-- Before, the service logged in as the owner, which is the container's POSTGRES_USER and therefore a
-- superuser, and only then ran SET ROLE audit_rw: a single RESET ROLE gave every privilege back (spec §2,
-- AuditRecord: "the service role has INSERT and SELECT only"). Now there is nothing to reset to.
--
-- Flyway still runs as the owner and fills ${appUser} and ${appPassword} from the service's own datasource
-- settings (application.yaml). To change the password later: ALTER ROLE ... PASSWORD as the owner.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appUser}') THEN
        EXECUTE format('CREATE ROLE %I LOGIN', '${appUser}');
    END IF;
    EXECUTE format('ALTER ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS INHERIT PASSWORD %L',
                   '${appUser}', '${appPassword}');
    EXECUTE format('GRANT audit_rw TO %I', '${appUser}');
END
$$;

-- Nobody but the owner creates objects in the schema (PostgreSQL 15+ already revokes this from PUBLIC).
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
