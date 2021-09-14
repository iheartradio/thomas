
FROM amazon/dynamodb-local

WORKDIR /home/dynamodblocal

# make database directory and change owner to dynamodb user
RUN mkdir ./db && chown -R 1000 ./db

CMD ["-jar", "DynamoDBLocal.jar", "-dbPath", "/home/dynamodblocal/db", "-sharedDb"]
VOLUME ["/home/dynamodblocal/db"]