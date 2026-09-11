# Used with `nomad agent -dev`. data_dir must exist on the Docker *host*
# (bind-mounted) so the docker driver can mount alloc dirs.
data_dir  = "/nomad/data"
bind_addr = "0.0.0.0"
