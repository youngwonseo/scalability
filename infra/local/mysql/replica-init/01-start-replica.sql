-- Runs once when a replica's data volume is created. The sns database and users arrive via replication.
CHANGE REPLICATION SOURCE TO
    SOURCE_HOST = 'mysql',
    SOURCE_USER = 'repl',
    SOURCE_PASSWORD = 'repl',
    SOURCE_AUTO_POSITION = 1,
    GET_SOURCE_PUBLIC_KEY = 1;
START REPLICA;
SET PERSIST super_read_only = ON;
