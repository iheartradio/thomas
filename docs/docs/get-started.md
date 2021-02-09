---
layout: page
title:  "Get Started"
section: "Get started"
position: 10
---


## Guide to to setup an A/B test platform using Thomas

### Step 1 setup a Mongo cluster for Thomas

Thomas runs on mongodb, dynamodb. It uses kafka for consuming analytics events.

You can run all of these deps using docker compose
```bash

cd dependencies
docker compose up -d
```


Or you can run individually using docker
```bash
docker run -p 27017-27019:27017-27019 --name mongodb mongo
```
### Step 1a setup dynamo cluster


```bash
docker run -p 8042:8000 amazon/dynamodb-local

``` 
If you want to persistent data 
```bash
docker run -p 8042:8000 -v ~/dynamodblocal/db:/home/dynamodblocal/db misoca/dynamodb-local-persist
```





### Step 2 create a http service application using thomas-http4s

`thomas-core` provides the core functionality through [a facade API](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html). If your product is using the typical server-client architecture, you can either incorporate this library into your existing service (it needs to be scala compatible though), or set up a standalone http service to serve this API. 

 
Create a new Scala project and in build.sbt add

```
libraryDependencies += "com.iheart" %% "thomas-http4s" % ThomasVersion
``` 
Then add a `Main` object by following the example of`ExampleAbtestServerApp` in http4Example

`sbt run` shall start the service locally.  
  
To config MongoDB host you can add this to an `application.conf` in your resource folder.
```
mongoDB.hosts = ["localhost:27017"]
```

If you want to use the Web UI, create another new Scala project and in build.sbt add

```
libraryDependencies += "com.iheart" %% "thomas-http4s" % ThomasVersion
``` 

Then add a `Main` object by following the example of`ExampleAbtestAdminUIApp` in http4Example

`sbt run` shall start the Web UI locally.


#### Step 2 Option 2b: Setting up with Play framework

Setting up a play application involves a little more effort. 

First create a new blank play application by following []the instructions here](https://www.playframework.com/getting-started). 

Then in build.sbt add

```
libraryDependencies += "com.iheart" %% "thomas-play" % ThomasVersion
```

Then clone the Thomas code base from github and copy all files from underneath playExample to your new play application. 

Then assuming you have a local MongoDB instance already up and running

`sbt run` shall start the service locally. 
 

Note that although these steps help you set up a basic http service for Thomas, it's likely that you might want to modify or enhance this service, e.g. add monitoring, logging, integration with an authentication system etc. Thomas, as a library, is designed such that it won't stand in the way however you want to build your service. 

### Step 3 include group assignments in analytics event report

Nothing Thomas can help here. To analyze the results, your client code needs to include the group assignments in the event metadata reported to your analytics platform. 


### Step 4 (Optional) write integration with your analytics
 
Your analytics platform probably support you to run A/B test results analysis. To use the Bayesian analysis tool though, you need to write 
an integration layer to help Thomas retrieve the data needed for such analysis. Please refer to [this dedicated page for running Bayesian analysis using Thomas](bayesian.html). 

