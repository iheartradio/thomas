---
layout: page
title:  "FAQ"
section: "FAQ"
position: 80
---

# Content Table 

* [How to add an override](#how-to-add-an-override)
* [How to get the test Id](#how-to-get-the-test-id)
* [How to terminate an A/B test](#how-to-terminate-an-ab-test)
* [How to do a feature roll out](#how-to-do-a-feature-roll-out)
* [How to run distributed group assignments](#how-to-run-distributed-group-assignments)
* [How to run Bayesian Analysis](#how-to-run-bayesian-analysis)

### All methods in API have corresponding endpoints in http service library (play or http4s).

# How do I fix a user to a certain group so that I can test the treatment

You can use the override feature to add overrides which are pairs of user Id and group name. 
[API for adding overides](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html#getOverrides(featureName:com.iheart.thomas.model.FeatureName):F[com.iheart.thomas.model.Feature])
  

# How to get the test ID

A/B tests are organized around "feature"s, you can run multiple A/B tests for the same feature, they just can't be scheduled to have any overlap with each other. For each test, there is a test Id which is generated when you created it. If you need the test Id for a specific test, you can use [this method](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html#getTestsByFeature(feature:com.iheart.thomas.model.FeatureName):F[Vector[lihua.Entity[com.iheart.thomas.model.Abtest]]]) which, and will respond with a list of all tests against this feature. In the list you can find more details of the tests including the test Id. 

# How to terminate/delete an A/B test

You would need the testId to use [this method](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html#terminate(test:com.iheart.thomas.model.TestId):F[Option[lihua.Entity[com.iheart.thomas.model.Abtest]]]). If a test already started, the test will be expired immediately. If the test is schedule to run in the future, it will be removed from the database. 


# How to do a feature roll out

You can use A/B test service to gradually roll out feature by incrementing the experiment group size in a series of tests. 
You start the first test without an end date, this will make the test run indefinitely. 
To gradually increase of experiment group size, use the [continue method](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html#continue(spec:com.iheart.thomas.model.AbtestSpec):F[lihua.Entity[com.iheart.thomas.model.Abtest]]) to create subsequent tests (all without end date), with larger and larger experiment group sizes, until you reach 100%.


# How to run distributed group assignments

Thomas provides a thomas-client that can pull down experiments metadata and calculate user assignments in parallel on local CPUs. It provides a [Java API class](https://iheartradio.github.io/thomas/api/com/iheart/thomas/client/JavaAssignments.html) so that it can be easily used in Java application or pyspark. To run it in pyspark, 
```
pyspark --packages com.iheart:thomas-client_2.11:LATEST_VERSION
```
[Check here for the latest version](https://github.com/iheartradio/thomas/releases) to use in place of `LATEST_VERSION`

Then in pyspark you can run
```python
client = sc._jvm.com.iheart.thomas.client.JavaAssignment.create("http://myhost/testsWithFeatures", )
client.assignments("813579", ["Feature_forYouBanner"], {"deviceId": "ax3263sdx11"})  

```
The first line creates the `client`, using `com.iheart.thomas.client.JavaAssignment.create`, you need to pass in a url string pointing to the endpoint corresponding to [this API method](https://iheartradio.github.io/thomas/api/com/iheart/thomas/API.html#getAllTestsCachedEpoch(time:Option[Long]):F[Vector[(lihua.Entity[com.iheart.thomas.model.Abtest],com.iheart.thomas.model.Feature)]]) (on play, if you follow the xample, it will be "yourHost/testsWithFeatures", on http4s it will be "yourHost/tests/cache").  You can also pass in a second optional argument - a timestamp for the as-of time for your assignments. For example, if you want to return assignments as of tomorrow 12:00PM, you need to get the epoch second time stamp of that time and pass in. You should reuse this `client` in your session, during the creation it makes an http call to the thomas A/B test http service and 
download all the relevant tests and overrides. So please avoid recreating it unnecessarily.


`client.assignments(userId, [tags], {user_meta})`  returns a Map (or hashmap if you are in python) of assignments. The keys of this Map will be feature names, and the values are the group names, the second and third arguments `[tags]` and `{user_meta}` are optional, ignore them if your tests don't requirement them. 

This solution works fine for pyspark with small amount of data. For large dataset, Pyspark introp with JVM is not efficient. 

Thomas also provides a tighter spark integration module `thomas-spark`, which provides an UDF and a function that works directly with 
DataFrame. The assignment computation is distributed through UDF

Here is an example on how to use this 
Start spark with the package

`pyspark --packages com.iheart:thomas-spark_2.11:LATEST_VERSION`
 
Inside pyspark shell

```python
from pyspark.mllib.common import _py2java
from pyspark.mllib.common import _java2py


ta = sc._jvm.com.iheart.thomas.spark.Assigner.create("https://MY_ABTEST_SERVICE_HOST/abtest/testsWithFeatures")

mockUserIds = [str(i) for i in range(100000)]
df = _py2java(sc.parallelize(mockUserIds).toDF("profileId"))

result = _java2py(ta.assignments(df, "My_Test_Feature"))

```
Note that some python to java conversion is needed since `thomas-spark` is written in Scala.  
 

 

# How to run Bayesian Analysis

Since Thomas does not come with an analytics solution, to analyze the A/B test results using Thomas's Bayesian utility, you need to write integration with your analytics solution. Please refer to [the dedicated page](bayesian.html) for detailed guide on this one.   
