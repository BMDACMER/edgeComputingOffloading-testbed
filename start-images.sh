# maven
mvn clean install -Dmaven.test.skip=true

# docker
## edge-node
cd ./edge-node || exit
docker rmi edge-node:v1.0
docker build -t edge-node:v1.0 .
## edge-controller
cd ../edge-controller || exit
docker rmi edge-controller:v1.0
docker build -t edge-controller:v1.0 .
## edge-experiment
cd ../edge-experiment || exit
docker rmi edge-experiment:v1.0
docker build -t edge-experiment:v1.0 .

