# Extra client settings on top of `nomad agent -dev`.
bind_addr = "0.0.0.0"

client {
  options = {
    "driver.allowlist" = "docker"
  }
}

plugin "docker" {
  config {
    volumes {
      enabled = true
    }
  }
}
