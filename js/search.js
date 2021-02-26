// When the user clicks on the search box, we want to toggle the search dropdown
function displayToggleSearch(e) {
  e.preventDefault();
  e.stopPropagation();

  closeDropdownSearch(e);
  
  if (idx === null) {
    console.log("Building search index...");
    prepareIdxAndDocMap();
    console.log("Search index built.");
  }
  const dropdown = document.querySelector("#search-dropdown-content");
  if (dropdown) {
    if (!dropdown.classList.contains("show")) {
      dropdown.classList.add("show");
    }
    document.addEventListener("click", closeDropdownSearch);
    document.addEventListener("keydown", searchOnKeyDown);
    document.addEventListener("keyup", searchOnKeyUp);
  }
}

//We want to prepare the index only after clicking the search bar
var idx = null
const docMap = new Map()

function prepareIdxAndDocMap() {
  const docs = [  
    {
      "title": "FAQ",
      "url": "/thomas/FAQ.html",
      "content": "Content Table How to add an override How to get the test Id How to terminate an A/B test How to manage user eligibility How to manage group meta How to do a feature roll out How to run distributed group assignments How to run Bayesian Analysis All methods in API have corresponding endpoints in http service library (play or http4s). How do I fix a user to a certain group so that I can test the treatment You can use the override feature to add overrides which are pairs of user Id and group name. API for adding overides How to get the test ID A/B tests are organized around “feature”s, you can run multiple A/B tests for the same feature, they just can’t be scheduled to have any overlap with each other. For each test, there is a test Id which is generated when you created it. If you need the test Id for a specific test, you can use this method which, and will respond with a list of all tests against this feature. In the list you can find more details of the tests including the test Id. How to terminate/delete an A/B test You would need the testId to use this method. If a test already started, the test will be expired immediately. If the test is schedule to run in the future, it will be removed from the database. How to manage user eligibility When you query abtest assignment, you can pass in the field meta a JSON object as meta information for that user. This JSON object is expected to be flat with string values only. In A/B tests you can set a user meta criteria in field userMetaCriteria, this user meta criteria is Json object is similiar to MongoDB’s json query. Other than exact match it supports $regex, $in, $versionStart, $versionRange, as well as combinators $and and $or The following example lists most of the criteria. { \"sex\" : \"female\", //matches users whose \"sex\" field is exactly \"female\" \"description\" : { \"%regex\" : \"[shinny|fancy]\" //matches users whose \"description\" field matches a regex \"[shinny|fancy]\" }, \"device\" : { \"%in\": [\"iphone\",\"ipad\"] //matches users whose \"device\" is one of the two strings \"iphone\" and \"ipad\" }, \"%or\": { //matches users whose \"city\" is \"LA\" or \"state\" is \"NY\" \"city\": \"LA\", \"state\": \"NY\" }, \"age\" : { \"%gt\" : 32 //matches age older than 32, other compartor includes %ge, %lt and %le }, \"clientVer\": { \"%versionStart\" : \"1.0.0\" //special filter for version strings. Matches users whose \"clientVer\" is later than \"1.0.0\" }, \"androidVer\": { \"%versionRange\" : [\"2.0\", \"3.1\"] //special filter for version strings. Matches users whose \"androidVer\" is between than \"2.0\" and \"3.1\" } } The combinator %or here is written as an object, which is convenient but also means field names cannot be duplicated. In case where you need to have multiple criteria on the same field within an %or, you also use an array of objects. For example: { \"%or\": [ { \"city\": \"LA\" }, { \"city\": \"NY\" } ] } You can manage this user criteria using the CLI which can be found and downloaded here. Then to show the current User meta criteria for a feature run ./thomas-cli_XXX.jar userMetaCriteria show -f MY_FEATUR_ENAME --host YOUR_HOST --rootPath YOUR_ROOT_PATH To update it you can write your criteria json in a file and use the following command to update ./thomas-cli_XXX.jar userMetaCriteria update --criteriaFile crit.json --new -f MY_FEATUR_ENAME --host YOUR_HOST --rootPath YOUR_ROOT_PATH How to manage group meta Optionally when you get a group assignment, the service can return the associated group metadata you set the group. The best way to manage group meta is to use the thomas CLI which can be found and downloaded here. Download that thomas-cli_XXX.jar and give it the execution permission. chmod +x thomas-cli_XXX.jar Then to show the current group meta for a feature run ./thomas-cli_XXX.jar groupMeta show -f MY_FEATUR_ENAME --host YOUR_HOST --rootPath YOUR_ROOT_PATH To edit/add a group meta ./thomas-cli_XXX.jar groupMeta add --meta '{ \"A\" : {\"newFeature\": 2 }, \"B\" : { \"newFeature\": 1 }}' -f MY_FEATUR_ENAME --host YOUR_HOST --rootPath YOUR_ROOT_PATH if the test already started, you will get an error message The latest test is already started, if you want to automatically create a new revision, please run the command again with “–new” flag As the message suggest, if you want to create a new revision of the test that starts immediately, run the same command again but add a --new flag. ./thomas-cli_XXX.jar groupMeta add --new --meta '{ \"A\" : {\"newFeature\": 2 }, \"B\" : { \"newFeature\": 1 }}' -f MY_FEATUR_ENAME --host YOUR_HOST --rootPath YOUR_ROOT_PATH How to do a feature roll out You can use A/B test service to gradually roll out feature by incrementing the experiment group size in a series of tests. You start the first test without an end date, this will make the test run indefinitely. To gradually increase of experiment group size, use the continue method to create subsequent tests (all without end date), with larger and larger experiment group sizes, until you reach 100%. How to run distributed group assignments Thomas provides a thomas-client that can pull down experiments metadata and calculate user assignments in parallel on local CPUs. It provides a Java API class so that it can be easily used in Java application or pyspark. To run it in pyspark, pyspark --packages com.iheart:thomas-client_2.11:LATEST_VERSION Check here for the latest version to use in place of LATEST_VERSION Then in pyspark you can run client = sc._jvm.com.iheart.thomas.client.JavaAssignment.create(\"http://myhost/testsWithFeatures\", ) client.assignments(\"813579\", [\"Feature_forYouBanner\"], {\"deviceId\": \"ax3263sdx11\"}) The first line creates the client, using com.iheart.thomas.client.JavaAssignment.create, you need to pass in a url string pointing to the endpoint corresponding to this API method (on play, if you follow the xample, it will be “yourHost/testsWithFeatures”, on http4s it will be “yourHost/tests/cache”). You can also pass in a second optional argument - a timestamp for the as-of time for your assignments. For example, if you want to return assignments as of tomorrow 12:00PM, you need to get the epoch second time stamp of that time and pass in. You should reuse this client in your session, during the creation it makes an http call to the thomas A/B test http service and download all the relevant tests and overrides. So please avoid recreating it unnecessarily. client.assignments(userId, [tags], {user_meta}) returns a Map (or hashmap if you are in python) of assignments. The keys of this Map will be feature names, and the values are the group names, the second and third arguments [tags] and {user_meta} are optional, ignore them if your tests don’t requirement them. This solution works fine for pyspark with small amount of data. For large dataset, Pyspark introp with JVM is not efficient. Thomas also provides a tighter spark integration module thomas-spark, which provides an UDF and a function that works directly with DataFrame. The assignment computation is distributed through UDF Here is an example on how to use this in pyspark: Start spark with the package pyspark --packages com.iheart:thomas-spark_2.11:LATEST_VERSION Inside pyspark shell, first create the instance of an A/B test Assigner ta = sc._jvm.com.iheart.thomas.spark.Assigner.create(\"https://MY_ABTEST_SERVICE_HOST/abtest/testsWithFeatures\") Then you can use it add a column to an existing DataFrame from pyspark.mllib.common import _py2java from pyspark.mllib.common import _java2py mockUserIds = spark.createDataFrame([(\"232\",), (\"3609\",), (\"3423\",)], [\"uid\"]) result = _java2py(sc, ta.assignments(_py2java(sc, mockUserIds), \"My_Test_Feature\", \"uid\")) Note that some python to java conversion is needed since thomas-spark is written in Scala. The Assigner also provides a Spark UDF assignUdf. You can call it with a feature name to get an UDF that returns the assignment for that abtest feature. spark._jsparkSession.udf().register(\"assign\", ta.assignUdf(\"My_TEST_FEATURES\")) sqlContext.registerDataFrameAsTable(mockUserIds, \"userIds\") result = sql(\"select uid, assign(uid) as assignment from userIds\") Or instead of registering the udf, you can use it through a python function from pyspark.sql.column import Column from pyspark.sql.column import _to_java_column from pyspark.sql.column import _to_seq from pyspark.sql.functions import col def assign(col): _javaAssign = ta.assignUdf(\"My_TEST_FEATURES\") return Column(_javaAssign.apply(_to_seq(sc, [col], _to_java_column))) mockUserIds.withColumn('assignment', assign(col('uid'))).show() In scala, it’s more straightforward. import spark.implicits._ import org.apache.spark.sql.functions.col val mockUserIds = (1 to 10000).map(_.toString).toDF(\"userId\") val assigner = com.iheart.thomas.spark.Assigner.create(\"https://MY_ABTEST_SERVICE_HOST/abtest/testsWithFeatures\") mockUserIds.withColumn(\"assignment\", assigner.assignUdf(\"My_TEST_FEATURES\")(col(\"userId\"))) assigner.assignUdf assigns based on the test data it retrieves when it’s created from Assigner.create. If you have a long running job, e.g. in Spark stream, you might want a udf that keeps test data updated, so that over a longer period of time it keeps assigning based on latest test data from server. In that case, you can use the com.iheart.thomas.AutoRefreshAssigner import concurrent.duration._ val assigner = com.iheart.thomas.spark.AutoRefreshAssigner( url = \"https://MY_ABTEST_SERVICE_HOST/abtest/testsWithFeatures\", refreshPeriod = 10.minutes ) mockUserIds.withColumn(\"assignment\", assigner.assignUdf(\"My_TEST_FEATURES\")(col(\"userId\"))) The refreshPeriod dictates how often the test data is retrieved from the A/B test service per spark partition. How to run Bayesian Analysis Since Thomas does not come with an analytics solution, to analyze the A/B test results using Thomas’s Bayesian utility, you need to write integration with your analytics solution. Please refer to the dedicated page for detailed guide on this one."
    } ,    
    {
      "title": "Bayesian Analysis",
      "url": "/thomas/bayesian.html",
      "content": "Thomas provides a module thomas-analysis that can be used to perform Bayesian analysis for A/B test results. This library is built using the wonderful Bayesian inference library Rainier . Using this thomas-analysis, users can write analysis tools that compute a posterior distribution of the effect of the treatment v.s. control using Markov Chain Monte Carlo. In its essence, the A/B test analysis’s main task is that, given the observed difference in analytics results between multiple treatment groups, pick the treatment that works best towards the goal for the product. The traditional frequentist approach has been trying to determine whether the difference observed is statistically significantly or not. The Bayesian analysis allows us to answer a different question: given the observed difference, what will be the risk of picking one treatment over another. In another sentence, what will be the maximum reduction of KPI in the scenario when one treatment is chosen over another but the real difference is the opposite of the one saw in the experiment. The Bayesian A/B analysis tool in Thomas produces results that look like the following. Group: Treatment A Count: 53179 Chance of better than control: 0.82692 Expected Benefit: 0.03209179240152402 Median Benefit: 0.02028449645941638 Risk of Using: -0.014472377020694363 Risk of Not Using: -0.05524666066837561 Plot: | | ○ | ○○ | ∘○· ○○ ∘ | ○○○○○○ ○· | ∘∘·○○○○○○○○○·· | ∘○○○○○○○○○○○○○○∘ | ∘○○○○○○○○○○○○○○○○·○ | ·○○○○○○○○○○○○○○○○○○○○ | ○○○○○○○○○○○○○○○○○○○○○○ | ·○○○○○○○○○○○○○○○○○○○○○○ | ∘∘○○○○○○○○○○○○○○○○○○○○○○○∘∘ | ∘∘○○○○○○○○○○○○○○○○○○○○○○○○○○○∘ | ·○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘ | ∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘ | ∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘· | ·∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘· | ○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○ | ·∘○∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘∘ | ·∘·○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○··· |··∘∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘∘·········· · · |--------|--------|--------|--------|--------|--------|--------|--------|-------- -0.039 -0.023 -0.007 0.010 0.026 0.042 0.058 0.075 0.091 To use these utilities users need to implement integration with data retrieval from their analytical system. The core class that provides the Bayesian analysis is AnalysisAPI provided in thomas-client This API is parameterized to the effect type F[_] and KPI model type K trait AnalysisAPI[F[_], K &lt;: KPIModel] KPI stands for Key Performance Indicator, it is what we use to evaluate the effect of a treatment. One example is the conversion (or click through) rate, another example is average user engagement time. Each KPI can be modeled as a certain type of distribution. For example conversion rate is often modeled using the Beta distribution. One can choose to use the Gamma distribution to model the user engagement time. In fact these are two types of KPI distributions Thomas supports out of box at the time of writing. User can implement their own KPIDistribution, it will require knowledge of Bayesian inference and Rainier the MCMC library used in Thomas. Thus depending on which type of KPI you are analyzing, you need to instantiate different type of AnalysisAPI. Take Gamma KPI distribution as an example: import com.iheart.thomas.client._ import com.iheart.thomas.client.AbtestClient.HttpServiceUrlsPlay import cats.effect.IO import com.iheart.thomas.analysis._ import concurrent.ExecutionContext.Implicits.global //these are Url to your Thomas http service instance that provides tests and kpi endpoints val httpServiceUrl = new HttpServiceUrlsPlay(\"http://localhost/internal\") implicit val conextShift = IO.contextShift(global) Http4SAbtestClient.resource[IO](httpServiceUrl, global).use { implicit client =&gt; implicit val measurable: Measurable[IO, Measurements, LogNormalKPIModel] = null //user needs to implement a Measurable, null used here so that code compiles val analysisAPI: AnalysisAPI[IO, LogNormalKPIModel] = implicitly //do something with analysisAPI IO(println(\"done\")) }.unsafeRunSync Note that in this code the user needs to provide a Client instance which will be used to retrieve A/B tests information and manage KPI from the Thomas service. Uer also needs to implement a GammaMeasurable, an alias for Measurable. This trait is responsible for getting the raw measurements of users of each group from the Analytics data. Once we have an instance of AnalysisAPI, we can initialize a KPI with prior distribution using historical data update the KPI’s prior distribution using more recent historical data assess the results of an A/B test For more detail regarding these methods please check AnalysisAPI’s API documentation"
    } ,    
    {
      "title": "Core concepts",
      "url": "/thomas/core.html",
      "content": "Core concepts Feature - a string representing a feature in the product to be A/B tested. A/B tests in Thomas are organized by the feature they are testing against. For each feature there is at most one ongoing test at a specific time. A/B test treatment group assignments are by features, no by specific tests. That is, when Thomas receives an inquiry about the treatment group assignments for a user, it returns a list of pairs of feature and group name. Specific test ids are hidden from such inquiries. For a client to determine which treatment to give for a feature, all it needs is the assigned group name for that feature. A/B test - represents a single experiment that gives different treatments to different groups of users for a certain feature during a period. It’s often that people run multiple rounds of A/B tests to determine the optimal treatment for a feature. In Thomas, users can create one A/B test after another testing a specific feature. This series of A/B tests can be deemed as evolving versions of the experiment. The A/B test’s group setup becomes immutable after it starts. To revise a running A/B test experiment, user has to terminate it and start a new one. This immutability is required to guarantee the correctness of the history as well as enabling the distributed assignment computation. For more details about the metadata in an A/B test, check the API documentation of AbtestSpec Eligibility Control - Mechanism to determine which users are eligible for which experiments. When a feature is not available to certain users, it is desirable to avoid providing any group assignment for these users. For one thing, it may become a source of false report in analytic data. This feature also allows targeting a subset of all users for a certain experiment. User Meta - When client send a group assignment inquiry request, it can include a Json object as the meta data for the user. This then can be used for eligibility control. A/B tests can be set with a filtering criteria to determine which users are eligible based on the user meta passed in. These criteria is written in query language in JSON format. Group Meta - a meta data associated with a treatment group that is included in group assignment response. This metadata could potentially be used to determine the treatment end-user receives. Client could, instead of hard code different behavior based on group names, use some configuration to control the behavior. The group meta can provide such configurations. Thus different group assignment can result in different treatment thanks to the different configurations in their group meta. Overrides - to fix group assignment for certain users. User assignment is stochastic which could be tricky when QAing an A/B tests. Overrides allow users to fix group assignments for certain users so that QA can test expected behavior from the product for those users."
    } ,    
    {
      "title": "Get Started",
      "url": "/thomas/get-started.html",
      "content": "Guide to to setup an A/B test platform using Thomas Step 1 setup a Mongo cluster for Thomas Thomas runs on mongodb, dynamodb. It uses kafka for consuming analytics events. You can run all of these deps using docker compose cd dependencies docker compose up -d Or you can run individually using docker docker run -p 27017-27019:27017-27019 --name mongodb mongo Step 1a setup dynamo cluster docker run -p 8042:8000 amazon/dynamodb-local If you want to persistent data docker run -p 8042:8000 -v ~/dynamodblocal/db:/home/dynamodblocal/db misoca/dynamodb-local-persist Step 2 create a http service application using thomas-http4s thomas-core provides the core functionality through a facade API. If your product is using the typical server-client architecture, you can either incorporate this library into your existing service (it needs to be scala compatible though), or set up a standalone http service to serve this API. Create a new Scala project and in build.sbt add libraryDependencies += \"com.iheart\" %% \"thomas-http4s\" % ThomasVersion Then add a Main object by following the example ofExampleAbtestServerApp in http4Example sbt run shall start the service locally. To config MongoDB host you can add this to an application.conf in your resource folder. mongoDB.hosts = [\"localhost:27017\"] If you want to use the Web UI, create another new Scala project and in build.sbt add libraryDependencies += \"com.iheart\" %% \"thomas-http4s\" % ThomasVersion Then add a Main object by following the example ofExampleAbtestAdminUIApp in http4Example sbt run shall start the Web UI locally. Step 2 Option 2b: Setting up with Play framework Setting up a play application involves a little more effort. First create a new blank play application by following []the instructions here](https://www.playframework.com/getting-started). Then in build.sbt add libraryDependencies += \"com.iheart\" %% \"thomas-play\" % ThomasVersion Then clone the Thomas code base from github and copy all files from underneath playExample to your new play application. Then assuming you have a local MongoDB instance already up and running sbt run shall start the service locally. Note that although these steps help you set up a basic http service for Thomas, it’s likely that you might want to modify or enhance this service, e.g. add monitoring, logging, integration with an authentication system etc. Thomas, as a library, is designed such that it won’t stand in the way however you want to build your service. Step 3 include group assignments in analytics event report Nothing Thomas can help here. To analyze the results, your client code needs to include the group assignments in the event metadata reported to your analytics platform. Step 4 (Optional) write integration with your analytics Your analytics platform probably support you to run A/B test results analysis. To use the Bayesian analysis tool though, you need to write an integration layer to help Thomas retrieve the data needed for such analysis. Please refer to this dedicated page for running Bayesian analysis using Thomas."
    } ,    
    {
      "title": "Home",
      "url": "/thomas/",
      "content": "Thomas is a modular Scala library for setting up a service for running A/B tests and multi-arm bandits. Major Features Real-time hash-based assignment High performant and resilient assignment HTTP service A/B test experiment evolution with consistent user assignments Bayesian analysis utilities Advanced eligibility control (targeted audience) Mutually exclusive experiments Distributed assignment / Spark support Web UI for management A/B test based dynamic configuration CLI for development automation Bayesian multi-armed bandits with Kafka integration Real-time hash-based assignment Thomas’ A/B group assignment for users is always done real-time by a mathematical function that deterministically and uniformly distributes users into different groups. This function takes in a timestamp, a user Id and their relevant meta info, it then returns the assignments of all A/B test experiments running at that time. Thomas does not batch pre-assign users nor does it store the assignments anywhere. Every time you ask Thomas for group assignments of a user, it will run that mathematical function to answer you. High performant and resilient assignment HTTP service A/B test information retrieval is an overhead for all features running A/B tests. High performance is an uncompromisable top priority for Thomas since the very beginning. Thomas’ service is implemented using a high performant scala http framework called http4s. More importantly, it keeps all necessary data in memory when serving user assignment requests to eliminate all IO overheads. On production Thomas handles 1K-2K requests per second at 1-2 ms response time. Thomas is built to be resilient when serving assignment requests. Using Mau, Thomas web service can withstand a relatively long backend DB downtime, during which the assignments requests continue to be served using last retrieved tests data. Thus when databases go offline, the management services become unavailable, but the assignment service remain functioning. A/B test experiment evolution with consistent user assignments It’s common that an A/B test consists of several rounds of experiments, sometimes introducing new treatment groups, sometimes changing the size of exiting groups. It is often preferable to avoid reassigning existing users within each group which will cause an abrupt discontinuity in their user experiences. Thomas supports such experiment evolution with consistent user assignments. When a new experiment of an A/B test is rolled out with a different group setup, Thomas can be set to either completely redistribute all users uniformly or keep the user assignment as consistent as possible. When a group is enlarged, all of its existing users will remain in it; when a group is shrunk, a minimally required portion of its existing users will be reassigned to other groups. The best way to demonstrate is through the following examples. Example 1: new treatment group In this example, a new treatment group C is introduced. To make space for this new group the size of group B is decreased, while the size of A is unchanged. In this scenario, Thomas will reassign part of the users in group B to the new group C while all users in group A remains in group A. Example 2: resize of existing groups In this example, the sizes of the groups are changed. Group A is enlarged, group B is shrunk. Thomas will reassign just enough users from B to A but no more than necessary. This also enables Thomas to double as a valid gradual feature roll-out system. A small treatment group can increase its size incrementally to roll out a feature. There are no disruptions of user experience in this process thanks to the consistent user assignment. Also note that Thomas does this without deviating from the real-time assignment algorithm. Bayesian analysis utilities Thomas provides a module thomas-analysis that can be used to perform Bayesian analysis for A/B test results. This library is built using the wonderful Bayesian inference library Rainier. Using this thomas-analysis, users can write analysis tools that compute a posterior distribution of the effect of the treatment v.s. control using Markov Chain Monte Carlo. In its essence, the A/B test analysis’s main task is that, given the observed difference in analytics results between multiple treatment groups, pick the treatment that works best towards the goal for the product. The traditional frequentist approach has been trying to determine whether the difference observed is statistically significantly or not. The Bayesian analysis allows us to answer a different question: given the observed difference, what will be the risk of picking one treatment over another. In another sentence, what will be the maximum reduction of KPI in the scenario when one treatment is chosen over another but the real difference is the opposite of the one saw in the experiment. The Bayesian A/B analysis tool in Thomas produces results that look like the following. Group: Treatment A Count: 53179 Chance of better than control: 0.82692 Expected Benefit: 0.03209179240152402 Median Benefit: 0.02028449645941638 Risk of Using: -0.014472377020694363 Risk of Not Using: -0.05524666066837561 Plot: | | ○ | ○○ | ∘○· ○○ ∘ | ○○○○○○ ○· | ∘∘·○○○○○○○○○·· | ∘○○○○○○○○○○○○○○∘ | ∘○○○○○○○○○○○○○○○○·○ | ·○○○○○○○○○○○○○○○○○○○○ | ○○○○○○○○○○○○○○○○○○○○○○ | ·○○○○○○○○○○○○○○○○○○○○○○ | ∘∘○○○○○○○○○○○○○○○○○○○○○○○∘∘ | ∘∘○○○○○○○○○○○○○○○○○○○○○○○○○○○∘ | ·○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘ | ∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘ | ∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘· | ·∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘· | ○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○ | ·∘○∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘∘ | ·∘·○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○··· |··∘∘○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○○∘∘·········· · · |--------|--------|--------|--------|--------|--------|--------|--------|-------- -0.039 -0.023 -0.007 0.010 0.026 0.042 0.058 0.075 0.091 Note that to use these utilities users need to implement integration with data retrieval from their analytical system. Advanced eligibility control (targeted audience) Eligibility control determines which users are eligible for which experiments. When a feature is not available to certain users, it is desirable to avoid providing any group assignment for these users. For one thing, it may become a source of false report in analytic data. This feature also allows targeting a subset of all users for a certain experiment. Thomas does not require any direct integration with in house user meta-data storage. When a client calls Thomas assignment service, it can pass in a small set of relevant metadata of the user. Experiments can be set with some eligibility criteria that filter users based on the metadata given. Here is an example of an assignment request that comes with a piece of meta data: { \"userId\": \"USERID\", \"meta\": { \"deviceId\": \"1231541sdfsd\", \"platform\": \"iOS\", \"clientVersion\": \"1.2.3\" } } This JSON object is expected to be flat with string values only. This then can be used for eligibility control. A/B tests can be set with a filtering criteria to determine which users are eligible based on the user meta passed in. These criteria is written in query language in JSON format. In A/B tests you can set a user meta criteria using a Json based query language. This query language similiar to MongoDB’s json query. Other than exact match it supports %regex, %in, %versionStart, %versionRange, as well as combinators %and and %or The following example lists most of the criteria. { \"sex\" : \"female\", //matches users whose \"sex\" field is exactly \"female\" \"description\" : { \"%regex\" : \"[shinny|fancy]\" //matches users whose \"description\" field matches a regex \"[shinny|fancy]\" }, \"device\" : { \"%in\": [\"iphone\",\"ipad\"] //matches users whose \"device\" is one of the two strings \"iphone\" and \"ipad\" }, \"%or\": { //matches users whose \"city\" is \"LA\" or \"state\" is \"NY\" \"city\": \"LA\", \"state\": \"NY\" }, \"clientVer\": { \"%versionStart\" : \"1.0.0\" //special filter for version strings. Matches users whose \"clientVer\" is later than \"1.0.0\" }, \"androidVer\": { \"%versionRange\" : [\"2.0\", \"3.1\"] //special filter for version strings. Matches users whose \"androidVer\" is between than \"2.0\" and \"3.1\" } } The combinator %or here is written as an object, which is convenient but also means field names cannot be duplicated. In case where you need to have multiple criteria on the same field within an %or, you also use an array of objects. For example: { \"%or\": [ { \"city\": \"LA\" }, { \"city\": \"NY\" } ] } Mutually exclusive experiments Thomas provides an easy way to support mutually exclusive A/B test experiments, i.e., a user could join either experiment but not both. An experiment can be set with an arbitrary numeric range within the range 0 to 1. A/B test experiments with non-overlapping ranges are mutually exclusive with each other. Distributed assignment / Spark support Thomas provides a thomas-client that can pull down experiments metadata and calculate user assignments in parallel on local CPUs. It also provides a Java API in thomas-spark usable in Spark and Pyspark. This would allow users to efficiently batch process data according to A/B test group assignment without any extra workload to a centralized A/B test service. For more details, go to this FAQ section Web UI for management Thomas supports tests management through a REST Http service. It also provides a Web UI with multi-tier per feature access control. A/B test based dynamic configuration Feature settings can be embedded into the A/B groups, and is returned together with the A/B group assignment. Application code can read these feature settings instead of toggling feature settings based on assigned A/B group. This setup has several advantages: It decouples client code from A/B test knowledge Changing feature settings no longer require modifying client code It keeps and manages feature settings and A/B groups at the same place, i.e. A/B test Web UI. CLI for development automation Thomas provides a command line interface to support development automation, e.g. programmable test management. Bayesian multi-armed bandits with Kafka integration Thomas can run multi-armed bandits using Thompson Sampling - distribute users to treatments based on the probability distribution of each treatment being the most optimal. This feature is still being actively developed. Modular and lightweight Thomas is developed in small modules so that users can pick what they need. thomas-core: core logic and algorithms thomas-http4s: library for creating an HTTP service using Http4s, also runs out of box thomas-http4s-example: an example Thomas http service application using thomas-http4s and Http4s Framework thomas-mongo: data access layer using MongoDB thomas-client: the distributed client that provides assignment by running the algorithm locally thomas-analysis: Bayesian analysis utilities thomas-cli: Command line interface using thomas-client thomas-bandit: WIP core logic for Bayesian multi-arm bandit thomas-spark: Integration with Spark for distributed batch assignment thomas-dynamo: WIP dynamo DB based Data access layer for multi-arm bandit related data thomas-stream: WIP streaming process of analytics data thomas-kafka: WIP integration of thomas stream and kafka for bandit thomas-testkit: Supports for testing systems built on Thomas thomas-stress: Stress tests for Thomas web services Ready to get started? Please follow the guide here. Contribute We welcome all feedback and questions. You can either submit a github issue or raise it in Thomas’ gitter channel Any contribution, big or small, are truly appreciated. We open source Thomas because we believe that a community can build a A/B test library better than a single organization. Code of conduct See our Code of Conduct file. Copyright and License All code is available to you under the Apache 2 license, available in the COPY file. Copyright iHeartMedia + Entertainment, Inc., 2018-2019."
    } ,        
  ];

  idx = lunr(function () {
    this.ref("title");
    this.field("content");

    docs.forEach(function (doc) {
      this.add(doc);
    }, this);
  });

  docs.forEach(function (doc) {
    docMap.set(doc.title, doc.url);
  });
}

// The onkeypress handler for search functionality
function searchOnKeyDown(e) {
  const keyCode = e.keyCode;
  const parent = e.target.parentElement;
  const isSearchBar = e.target.id === "search-bar";
  const isSearchResult = parent ? parent.id.startsWith("result-") : false;
  const isSearchBarOrResult = isSearchBar || isSearchResult;

  if (keyCode === 40 && isSearchBarOrResult) {
    // On 'down', try to navigate down the search results
    e.preventDefault();
    e.stopPropagation();
    selectDown(e);
  } else if (keyCode === 38 && isSearchBarOrResult) {
    // On 'up', try to navigate up the search results
    e.preventDefault();
    e.stopPropagation();
    selectUp(e);
  } else if (keyCode === 27 && isSearchBarOrResult) {
    // On 'ESC', close the search dropdown
    e.preventDefault();
    e.stopPropagation();
    closeDropdownSearch(e);
  }
}

// Search is only done on key-up so that the search terms are properly propagated
function searchOnKeyUp(e) {
  // Filter out up, down, esc keys
  const keyCode = e.keyCode;
  const cannotBe = [40, 38, 27];
  const isSearchBar = e.target.id === "search-bar";
  const keyIsNotWrong = !cannotBe.includes(keyCode);
  if (isSearchBar && keyIsNotWrong) {
    // Try to run a search
    runSearch(e);
  }
}

// Move the cursor up the search list
function selectUp(e) {
  if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index) && (index > 0)) {
      const nextIndexStr = "result-" + (index - 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Move the cursor down the search list
function selectDown(e) {
  if (e.target.id === "search-bar") {
    const firstResult = document.querySelector("li[id$='result-0']");
    if (firstResult) {
      firstResult.firstChild.focus();
    }
  } else if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index)) {
      const nextIndexStr = "result-" + (index + 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Search for whatever the user has typed so far
function runSearch(e) {
  if (e.target.value === "") {
    // On empty string, remove all search results
    // Otherwise this may show all results as everything is a "match"
    applySearchResults([]);
  } else {
    const tokens = e.target.value.split(" ");
    const moddedTokens = tokens.map(function (token) {
      // "*" + token + "*"
      return token;
    })
    const searchTerm = moddedTokens.join(" ");
    const searchResults = idx.search(searchTerm);
    const mapResults = searchResults.map(function (result) {
      const resultUrl = docMap.get(result.ref);
      return { name: result.ref, url: resultUrl };
    })

    applySearchResults(mapResults);
  }

}

// After a search, modify the search dropdown to contain the search results
function applySearchResults(results) {
  const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
  if (dropdown) {
    //Remove each child
    while (dropdown.firstChild) {
      dropdown.removeChild(dropdown.firstChild);
    }

    //Add each result as an element in the list
    results.forEach(function (result, i) {
      const elem = document.createElement("li");
      elem.setAttribute("class", "dropdown-item");
      elem.setAttribute("id", "result-" + i);

      const elemLink = document.createElement("a");
      elemLink.setAttribute("title", result.name);
      elemLink.setAttribute("href", result.url);
      elemLink.setAttribute("class", "dropdown-item-link");

      const elemLinkText = document.createElement("span");
      elemLinkText.setAttribute("class", "dropdown-item-link-text");
      elemLinkText.innerHTML = result.name;

      elemLink.appendChild(elemLinkText);
      elem.appendChild(elemLink);
      dropdown.appendChild(elem);
    });
  }
}

// Close the dropdown if the user clicks (only) outside of it
function closeDropdownSearch(e) {
  // Check if where we're clicking is the search dropdown
  if (e.target.id !== "search-bar") {
    const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
    if (dropdown) {
      dropdown.classList.remove("show");
      document.documentElement.removeEventListener("click", closeDropdownSearch);
    }
  }
}
