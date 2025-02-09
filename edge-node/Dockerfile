FROM guohaopod/ubuntu-22-jdk-17-djl-0.21-cpu:v1.1
LABEL maintainer=guohao
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
RUN echo 'Asia/Shanghai' >/etc/timezone
ENV PATH /root/guohao/jdk17/bin:${PATH}
COPY target/edge-node-1.0-SNAPSHOT.jar   /root/app.jar
WORKDIR /root
ENTRYPOINT ["sh","-c","java -Xms1024m -Xmx1024m -jar app.jar --spring_application_name=$spring_application_name"]