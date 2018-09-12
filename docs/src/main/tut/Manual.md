# Content Table 

* [How to create an A/B test](#how-do-i-create-an-ab-test)
* [How to add an override](#how-to-add-an-override)
* [How to get the test Id](#how-to-get-the-test-id)
* [How to terminate an A/B test](#how-to-terminate-an-ab-test)
* [How to do a feature roll out](#how-to-do-a-feature-roll-out)
* [How to retrieve assignments in pyspark](#how-to-get-the-assignments-in-pyspark)


# How do I create an A/B test 
 
Four things to be determined: 
1. number of groups, how many treatments you want to test in this one test 
2. which Key Performance Indicators (KPIs), i.e. metrics to be used for the a/b test 
3. duration of the tests
4. size of each group

Here are some tools for designing A/B test group size. 
https://www.evanmiller.org/ab-testing/sample-size.html

Once designed, your A/B test can be defined in the follow Json format 

```json

{
  "name": "A/B test against model v1.0",
  "feature": "Data Model",
  "author": "Kai", 
  "start": "2017-09-21T00:00:00.001-04:00",  
  "end": "2017-09-27T23:59:59.999-04:00",
  "groups": [ 
    {
      "name": "A",
      "size": 0.5
    },
    {
      "name": "B",
      "size": 0.5
    }
}

```

### Field definitions: 

**name**: name of the test, it's more like a note/description, **it is NOT an identifier**. 

**feature**: feature name of the treatment. This is an identifier with which feature code can determine for each user which treatment they get.

**author**: another notes field

**start**: start of the test

**end**: end of the test, optional, if not given the test will last indefinitely

**groups**: group definitions. group sizes don't have to add up to 1, but they cannot go beyond 1. If the sum group size is less than 1, it means that there is a portion (1 - the sum group size) of users won't be the in tests at all, you could make this group your control group.



# How to add an override

Users are assigned to groups randomly (but deterministically). You can, however, assign certain users to certain groups using this field, there is an dedicated endpoint to add such overrides. 

# How to get the test ID

A/B tests are organized around "feature"s, you can run multiple A/B tests for the same feature, they just can't be scheduled to have any overlap with each other. For each test, there is a test Id which is generated when you created it. If you need the test Id for a specific test, you can use this endpoint http://localhost:9000/internal/docs/swagger-ui/index.html?url=/internal/assets/swagger.json#!/routes/getByFeature). The endpoint asks a featureName, and will respond with a list of all tests scheduled and ran for this feature. In the list you can find more details of the tests including the test Id. 

# How to terminate an A/B test

If you want to stop an A/B test before it expires, you can use this endpoint
(http://localhost:9000/internal/docs/swagger-ui/index.html?url=/internal/assets/swagger.json#!/routes/terminate). You would need the testId to use this endpoint. If a test already started, the test will be expired immediately. If the test hasn't started, it will be removed from the database. 


# How to do a feature roll out

You can use A/B test service to gradually roll out feature by incrementing the experiment group size in a series of tests. 
You start the first test using the [create endpoint]( http://localhost:9000/internal/docs/swagger-ui/index.html?url=/internal/assets/swagger.json#/routes/create) to create a first test without an end date, this will make the test run indefinitely. 
For subsequent tests, use the [continue endpoint](http://localhost:9000/internal/docs/swagger-ui/index.html?url=/internal/assets/swagger.json#!/routes/continue). you will need to keep the `feature` the same in the subsequent tests and not setting the end date, in each test increase the group size of the experiment group, until you reach 100%


# How to get the assignments in pyspark

There is an abtest client jar published to internal maven repo that you can use in pyspark (or any spark job)
To use it use the following command:
```
pyspark --packages com.iheart:abtest-client_2.11:LATEST_VERSION 
```
[Check here for the latest version](https://github.com/iheartradio/thomas/releases) to use in place of `LATEST_VERSION`

Then in pyspark you can run
```python
client = sc._jvm.com.iheart.abtest.client.JavaAPI.create(yourServiceUrl)
client.assignments("813579", ["Feature_forYouBanner"], {"deviceId": "ax3263sdx11"})  

```
The first line creates the `client`, using `com.iheart.abtest.client.JavaAPI.create(yourServiceUrl)`, you need to pass in the full url of the public assignment endpoint of  deployed service. You can also pass in a second optional argument - a timestamp for the as-of time for your assignments. For example, if you want to return assignments as of tomorrow 12:00PM, you need to get the epoch second time stamp of that time and pass in. You should reuse this `client` in your session, during the creation it makes an http call to the abtest service and
download all the relevant tests and overrides (whitelists). So please avoid recreating it unnecessarily.


`client.assignments(userId, [tags], {user_meta})` returns a hashmap of assignments. The keys of this hashmap will be feature names, and the values are the group names, the second and third arguments `[tags]` and `{user_meta}` are optional, ignore them if your tests don't requirement them. 



