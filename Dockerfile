from choonghyun88/metatron

RUN rm -rf /app/metatron/metatron-discovery/
COPY . /app/metatron/metatron-discovery/

RUN cp /app/resources/application.yaml /app/metatron/metatron-discovery/discovery-server/src/main/resources/
WORKDIR /app/metatron/metatron-discovery/discovery-frontend/
RUN npm install

WORKDIR /app/metatron/metatron-discovery/
RUN mvn clean package -U -Dmaven.test.skip=true

WORKDIR /app
RUN /app/start-all.sh

EXPOSE 38000:80 38180:8180 30022:22 33306:3306 38081:8081 38090:8090 39000:9000 30000:10000 38082:8082 34200:4200 50090:50090
