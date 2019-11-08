---
layout: page
title:  "Get Started"
section: "Get started"
position: 10
---


## Guide to to setup an A/B test platform using Thomas

### Step 1 setup a Mongo cluster for Thomas

Thomas needs a basic data store to store test metadata. Since the amount of data is quite small and Thomas strives keep the data in memory, the requirement for this data store is minimum. Thomas is designed in a way so that it can support different data stores. As of now, only one MongoDB is supported through the module `thomas-mongo`. For local development, you just need to install and run a MongoDB instance locally.  

### Step 1a (Optional) setup dynamo cluster

If you want to try the Multi Arm Bandit Engine you need a dynamo cluster, you can use Docker to run one locally
```
docker run -p 8042:8000 amazon/dynamodb-local
``` 

### Step 2 create a http service application using either thomas-http4s or thomas-play

`thomas-core` provides the core functionality through [a facade API](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html). If your product is using the typical server-client architecture, you can either incorporate this library into your existing service (it needs to be scala compatible though), or set up a standalone http service to serve this API. 

#### Step 2 Option 1: incorporate Thomas-core library into existing scala http service application

Basically you need to instantiate the [Thomas facade API](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html) and have your http service delegate to it. To instantiate this API you need an implementation of the data access layer. Right now thomas-mongo provide one for the MongoDB. 

Since the instance maintains DB connection pool, it's a resource that needs to be managed. Depending on the paradigm of your existing http service application, you can follow [the example in thomas-http4s](https://iheartradio.github.io/thomas/api/com/iheart/thomas/http4s/AbtestService$.html) if it's pure functional, or [the example in thomas-play](https://iheartradio.github.io/thomas/api/com/iheart/thomas/play/APIProvider.html) if it's more traditionally OO.  


If you choose to set up a standalone service, you can utilize either `thomas-http4s` or `thomas-play`.
 
#### Step 2 Option 2a: Setting up with http4s

Create a new Scala project and in build.sbt add

```
libraryDependencies += "com.iheart" %% "thomas-http4s" % ThomasVersion
``` 
Then add a `Main` object by following the example of `com.iheart.thomas.http4s.ExampleAbtestServerApp`

Then assuming you have a local MongoDB instance already up and running
 
`sbt run` shall start the service locally.  
  
To config MongoDB host you can add this to an `application.conf` in your resource folder.
```
mongoDB.hosts = ["localhost:27017"]
```


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

