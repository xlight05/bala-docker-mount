docker build -t bala-docker-demo .
docker run -p 8080:8080 -v /home/anjana/dists:/dists bala-docker-demo
