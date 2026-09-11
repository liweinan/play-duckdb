variable "workers" {
  type    = number
  default = 2
}

job "spark-worker" {
  type = "service"

  group "worker" {
    count = var.workers

    task "worker" {
      driver = "docker"

      config {
        image        = "play-duckdb-spark:local"
        force_pull   = false
        network_mode = "play-duckdb_default"
        entrypoint   = ["/opt/spark/bin/spark-class"]
        args = [
          "org.apache.spark.deploy.worker.Worker",
          "--cores", "1",
          "--memory", "1g",
          "spark://spark-master:7077",
        ]
      }

      env {
        HTTP_PROXY           = ""
        HTTPS_PROXY          = ""
        http_proxy           = ""
        https_proxy          = ""
        NO_PROXY             = "minio,postgres,spark-master,spark-etl,localhost,127.0.0.1"
        no_proxy             = "minio,postgres,spark-master,spark-etl,localhost,127.0.0.1"
        SPARK_WORKER_MEMORY  = "1g"
        SPARK_WORKER_CORES   = "1"
      }

      resources {
        cpu    = 400
        memory = 1280
      }
    }
  }
}
