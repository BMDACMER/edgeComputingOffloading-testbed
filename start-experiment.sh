# maven
mvn clean install -Dmaven.test.skip=true

# docker
## edge-node
cd ./edge-node || exit
docker rmi edge-node:v2.0
docker build -t edge-node:v2.0 .
## edge-controller
cd ../edge-controller || exit
docker rmi edge-controller:v2.0
docker build -t edge-controller:v2.0 .
## edge-experiment
cd ../edge-experiment || exit
docker rmi edge-experiment:v2.0
docker build -t edge-experiment:v2.0 .

# kubernetes
## edge-node
cd ../k8s/edge || exit
for i in $(seq 1 10); do
  export spring_application_name=edge-node-$i
  envsubst <edge-node.yaml | kubectl apply -f -
done
## edge-controller
kubectl apply -f edge-controller.yaml

## edge-experiment
# todo: debug in local computer, NodePort for edge-node and controller
kubectl apply -f edge-experiment.yaml
