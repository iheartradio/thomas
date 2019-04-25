---
layout: page
title:  "Get Started"
section: "Get started"
position: 10
---


To setup your own A/B test platform using Thomas

### Step 1 setup a Mongo cluster for Thomas

Thomas needs a basic data store to store test metadata. Since the amount of data is quite small and Thomas strives keep the data in memory, the requirement for this data store is minimum. Thomas is designed in a way so that it can support different data stores. As of now, only one MongoDB is supported through the module `thomas-mongo`. For local development, you just need to install and run a MongoDB instance locally.  

### Step 2 create a http service application using either thomas-http4s or thomas-play

`thomas-core` provides the core functionality through [a facade API](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html). If your product is using the typical server-client architecture, you can either incorporate this library into your existing service (it needs to be scala compatible though), or you can utilize either `thomas-http4s` or `thomas-play` to setup a standalone http service to serve this API. 

#### incorporate Thomas-core library into existing scala http service application

You will need to instantiate the [Thomas facade API](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html). To instance this API you need an implementation of the data access layer. Right now thomas-mongo provide one for the MongoDB. 
Since the instance maintains DB connection pool, it's a resource that needs to be managed. Depending on the paradigm of your existing http service application, you follow [the example in thomas-http4s](https://iheartradio.github.io/thomas/api/com/iheart/thomas/http4s/AbtestService$.html) if it's pure functional, or [the example in thomas-play](https://iheartradio.github.io/thomas/api/com/iheart/thomas/play/APIProvider.html) if it's more traditionally OO.  

#### Setting up with http4s

Create a new Scala project and in build.sbt add

```
libraryDependencies += "com.iheart" %% "thomas-http4s" % ThomasVersion
``` 
Then add a `Main` object 

```tut
import com.iheart.thomas.http4s

```
```tut
object Main extends http4s.ExampleHtt4sApp 

```

Then assuming you have a local MongoDB instance already up and running
 
`sbt run` shall start the service locally. 
 
  
To config MongoDB host you can add this to an `application.conf` in your resource folder.
```
mongoDB.hosts = ["localhost:27017"]
```


#### Setting up with Play framework

Setting up a play application involves a little more effort. 

First create a new blank play application by following []the instructions here](https://www.playframework.com/getting-started). 

Then in build.sbt add

```
libraryDependencies += "com.iheart" %% "thomas-play" % ThomasVersion
```

Then clone the Thomas code base from github and copy all files from underneath playExample to your new play application. 

Then assuming you have a local MongoDB instance already up and running

`sbt run` shall start the service locally. 
 


### Step 3 include group assignments in analytics event report

Nothing Thomas can help here. To analyze the results, your client code needs to include the group assignments in the event metadata reported to your analytics platform. 


### Step 4 (Optional) write integration with your analytics
 
Your analytics platform probably support you to run A/B test results analysis. To use the Bayesian analysis tool though, you need to write 
an integration layer to help Thomas retrieve the data needed for such analysis. Please refer to [the dedicated page](bayesian.html) for detailed guide on this one.   

