

## Service for AB test

Replace the "Host" in the following Urls with the host you deployed the application

### To get all running tests

http://{Host}/tests

### To get all tests for a certain feature

http://{Host}/features/{featureName}/tests

### To query which groups a user is in 

You would need to use the following command in Terminal. Replace the `USERID` and `tags` and `deviceId` if you are interested in tests that are based on `deviceId`.

```bash
curl -X POST  -H "accept: application/json" -H \
"Content-Type: application/json" -d '{
  "userId": "USERID", 
  "tags": [ "iOSUser" ],
  "meta": { "deviceId": "1231541sdfsd" }
  }' \
"http://{Host}/users/groups/query"
```

### To get existing overrides 

http://{Host}/features/{featureName}/overrides


### To remove a override 

```bash
curl -X DELETE "http://{Host}/features/FEATURE_NAME/overrides/USERID" -H "accept: application/json"
```


As for how to create and run an AB test, see [here](/Manual.md)
