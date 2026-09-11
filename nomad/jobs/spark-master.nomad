job "spark-master" {
  type = "service"

  group "master" {
    count = 1

    task "master" {
      driver = "docker"

      config {
        image        = "play-duckdb-spark:local"
        force_pull   = false
        network_mode = "play-duckdb_default"
        hostname     = "spark-master"
        entrypoint   = ["/opt/spark/bin/spark-class"]
        args = [
          "org.apache.spark.deploy.master.Master",
          "--host", "spark-master",
          "--port", "7077",
          "--webui-port", "8080",
        ]
      }

      env {
        HTTP_PROXY  = ""
        HTTPS_PROXY = ""
        http_proxy  = ""
        https_proxy = ""
        NO_PROXY    = "minio,postgres,spark-master,spark-etl,localhost,127.0.0.1"
        no_proxy    = "minio,postgres,spark-master,spark-etl,localhost,127.0.0.1"
      }

      resources {
        cpu    = 200
        memory = 384
      }
    }
  }
}
