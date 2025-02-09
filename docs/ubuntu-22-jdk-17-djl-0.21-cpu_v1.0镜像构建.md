# ubuntu-22-jdk-17-djl-0.21-cpu_v1.0镜像构建





https://hub.docker.com/_/ubuntu/tags?page=1&name=22

```sh
docker pull ubuntu:22.04
```



```sh
docker run --name ubuntu22 -itd ubuntu:22.04 /bin/bash
```



```sh
docker exec -it ubuntu22 /bin/bash
```



```sh
cd root
mkdir guohao
```



```sh
docker cp jdk-17.0.5 ubuntu22:/root/guohao
```


```sh
docker cp ~/.djl.ai/ ubuntu22:/root/
```



```sh
docker commit ubuntu22 ubuntu-22-jdk-17-djl-0.21-cpu:v1.0
```

