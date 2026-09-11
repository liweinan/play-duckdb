variable "observe" {
  type    = string
  default = ""
}

job "spark-etl" {
  type = "batch"

  group "etl" {
    count = 1

    restart {
      attempts = 0
    }

    task "etl" {
      driver = "docker"

      config {
        image        = "play-duckdb-spark:local"
        force_pull   = false
        network_mode = "play-duckdb_default"
        hostname     = "spark-etl"
        entrypoint   = ["/opt/jobs/run_etl.sh"]
      }

      env {
        SPARK_MASTER      = "spark://spark-master:7077"
        SPARK_DRIVER_HOST = "spark-etl"
        OBSERVE           = "${var.observe}"
        AWS_REGION        = "us-east-1"
        AWS_ACCESS_KEY_ID = "admin"
        AWS_SECRET_ACCESS_KEY = "password"
        AWS_S3_ENDPOINT   = "http://minio:9000"
        HTTP_PROXY        = ""
        HTTPS_PROXY       = ""
        http_proxy        = ""
        https_proxy       = ""
        NO_PROXY          = "minio,postgres,spark-master,spark-etl,localhost,127.0.0.1"
        no_proxy          = "minio,postgres,spark-master,spark-etl,localhost,127.0.0.1"
      }

      resources {
        cpu    = 500
        memory = 1024
      }
    }
  }
}
