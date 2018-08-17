
## Development

For API docs locally go to

http://localhost:9000/docs/swagger-ui/index.html?url=/assets/swagger.json#!/routes


To run

 `sbt http/run`

To run with QA setting 

  `sbt http/run -Dconfig.resource=application.qa.conf`

To test

 `sbt http/test`

To Stress test

 `sbt stress/gatling:test`

