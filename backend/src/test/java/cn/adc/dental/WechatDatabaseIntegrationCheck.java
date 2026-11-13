package cn.adc.dental;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import tools.jackson.databind.json.JsonMapper;

/** Dedicated newly initialized loopback MySQL only. Test provider is never packaged in the runtime JAR. */
public final class WechatDatabaseIntegrationCheck {
    private static int checks, serial;
    private static MockMvc mvc;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("integration check "+checks);}
    static final class MutableClock extends Clock {
        Instant value=Instant.parse("2026-10-25T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return value;}
    }
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource(){
            String url=System.getenv("TEST_WECHAT_JDBC_URL");
            if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:13329/wechat_it_[a-f0-9]{12}\\?[^ ]+"))throw new IllegalStateException("owned fixture database required");
            var data=new DriverManagerDataSource(url,System.getenv("TEST_WECHAT_DB_USER"),System.getenv("TEST_WECHAT_DB_PASSWORD")) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var connection=super.getConnection();connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);
                    try(var sql=connection.createStatement()){sql.execute("SET time_zone='+00:00'");}return connection;
                }
            };data.setDriverClassName("com.mysql.cj.jdbc.Driver");return data;
        }
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean Clock clock(){return new MutableClock();}
        @Bean Store store(JdbcTemplate jdbc,Clock clock){return new Store(jdbc,clock);}
        @Bean PlatformTransactionManager transactions(DataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean WechatIdentityExchange fixtureExchange(){return new WechatIdentityExchange(){
            public boolean ready(){return true;}
            public Subject exchange(String code){return new Subject("fixture-app","fixture-open-"+code.substring(0,1));}
        };}
        @Bean WechatRuntime runtime(Store db,ObjectProvider<WechatIdentityExchange> adapter){return new WechatRuntime(true,true,"fixture-app",adapter,db);}
        @Bean WechatBindingStore bindings(Store db,ObjectProvider<Identity> identity,WechatRuntime runtime){return new WechatBindingStore(db,identity,runtime);}
        @Bean Identity identity(Store db,ObjectProvider<WechatBindingStore> bindings){return new Identity(db,bindings);}
        @Bean WechatPatientLoginService service(WechatRuntime runtime,WechatBindingStore bindings,ObjectProvider<Identity> identity,Store db){return new WechatPatientLoginService(runtime,bindings,identity,db);}
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> call(int status,String method,String path,Object body,String token)throws Exception{
        var builder=request(HttpMethod.valueOf(method),path);
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.contentType("application/json").content(JSON.writeValueAsString(body));
        var result=mvc.perform(builder).andReturn();check(result.getResponse().getStatus()==status);
        return JSON.readValue(result.getResponse().getContentAsByteArray(),Map.class);
    }
    private static String code(String group)throws Exception{return call(200,"POST","/api/v1/patient/wechat-binding-requests",Map.of("code",group+"-"+(++serial)),null).get("bindingCode").toString();}
    private static String login(String group)throws Exception{return call(200,"POST","/api/v1/patient/wechat-sessions",Map.of("code",group+"-"+(++serial)),null).get("token").toString();}
    private static Map<String,Object> bind(String code,long operation,String generation,String evidence){return Map.of("bindingCode",code,"operationId",Long.toString(operation),"expectedGeneration",generation,"evidenceReference",evidence,"confirmedInPerson",true);}
    private static Map<String,Object> unbind(long operation,String generation){return Map.of("operationId",Long.toString(operation),"expectedGeneration",generation,"evidenceReference","TEST-UNBIND","confirmed",true);}
    private static String path(long account){return "/api/v1/staff-admin/patient-wechat/accounts/"+account;}
    private static void rejectedSql(JdbcTemplate db,String sql,Object... params){try{db.update(sql,params);throw new AssertionError("constraint accepted invalid state");}catch(org.springframework.dao.DataAccessException expected){check(true);}}

    public static void main(String[] args)throws Exception{
        try(var context=new AnnotationConfigApplicationContext(Config.class)){
            var db=context.getBean(JdbcTemplate.class);var identity=context.getBean(Identity.class);var service=context.getBean(WechatPatientLoginService.class);var bindings=context.getBean(WechatBindingStore.class);
            var fixtureAdmin=new JdbcTemplate(new DriverManagerDataSource(System.getenv("TEST_WECHAT_JDBC_URL"),"root",""));
            mvc=MockMvcBuilders.standaloneSetup(new WechatPatientLoginApi(service),new WechatBindingApi(service,bindings),new IdentityApi(identity)).setControllerAdvice(new Errors()).build();
            String staff=identity.login("TEST-ADMIN","test-only-never-upload","员工").get("token").toString();
            String front=identity.login("TEST-FRONT","test-only-never-upload","员工").get("token").toString();
            String development=identity.login("TEST-P-201","test-only-never-upload","患者").get("token").toString();
            call(403,"POST","/api/v1/patient/wechat-sessions",Map.of("code","A-unbound"),null);
            String ticket=code("A");
            call(403,"POST","/api/v1/staff-admin/patient-wechat/requests/lookup",Map.of("bindingCode",ticket),front);
            call(200,"POST","/api/v1/staff-admin/patient-wechat/requests/lookup",Map.of("bindingCode",ticket),staff);
            var body=bind(ticket,3001,"0","TEST-VERIFY");
            var noConfirmation=new HashMap<>(body);noConfirmation.put("confirmedInPerson",false);
            call(400,"POST",path(201)+"/binding",noConfirmation,staff);
            call(200,"POST",path(201)+"/binding",body,staff);
            check(Boolean.TRUE.equals(call(200,"POST",path(201)+"/binding",body,staff).get("replayed")));
            var changed=new HashMap<>(body);changed.put("evidenceReference","DIFFERENT");call(409,"POST",path(201)+"/binding",changed,staff);
            check(db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE audit_id=3001",Integer.class)==1);
            String patient=login("A");check(call(200,"GET","/api/v1/me",null,patient).get("patientId").equals("501"));
            call(403,"GET",path(201),null,patient);
            check(!JSON.writeValueAsString(call(200,"GET",path(201),null,staff)).contains("openid"));

            rejectedSql(db,"UPDATE accounts SET wechat_app_id=?,wechat_open_id=?,wechat_binding_generation=1 WHERE account_id=202","fixture-app".getBytes(),"fixture-open-A".getBytes());
            rejectedSql(db,"UPDATE accounts SET wechat_app_id=? WHERE account_id=202","fixture-app".getBytes());
            rejectedSql(db,"UPDATE accounts SET wechat_binding_generation=-1 WHERE account_id=202");
            rejectedSql(db,"UPDATE accounts SET wechat_app_id=?,wechat_open_id=? WHERE account_id=101","fixture-app".getBytes(),"staff-open".getBytes());
            rejectedSql(db,"UPDATE accounts SET wechat_app_id=?,wechat_open_id=? WHERE account_id=202",new byte[0],"fixture-open".getBytes());
            db.update("UPDATE accounts SET wechat_app_id=?,wechat_open_id=? WHERE account_id=202","fixture-app".getBytes(),"fixture-open-a".getBytes());check(true);
            db.update("UPDATE accounts SET wechat_app_id=NULL,wechat_open_id=NULL WHERE account_id=202");
            call(200,"POST",path(201)+"/unbinding",unbind(3002,"1"),staff);
            call(401,"GET","/api/v1/me",null,patient);call(200,"GET","/api/v1/me",null,development);
            check(Boolean.TRUE.equals(call(200,"POST",path(201)+"/unbinding",unbind(3002,"1"),staff).get("replayed")));
            call(200,"POST",path(201)+"/binding",bind(code("A"),3003,"2","TEST-REBIND"),staff);
            String rebound=login("A");call(401,"GET","/api/v1/me",null,patient);call(200,"GET","/api/v1/me",null,rebound);
            String credential=db.queryForObject("SELECT credential_ref FROM accounts WHERE account_id=201",String.class);
            db.update("UPDATE accounts SET credential_ref='test-reset-disabled' WHERE account_id=201");call(401,"GET","/api/v1/me",null,rebound);db.update("UPDATE accounts SET credential_ref=? WHERE account_id=201",credential);
            String active=login("A");db.update("UPDATE patients SET active=0 WHERE patient_id=501");call(401,"GET","/api/v1/me",null,active);db.update("UPDATE patients SET active=1 WHERE patient_id=501");
            String accountActive=login("A");db.update("UPDATE accounts SET active=0 WHERE account_id=201");call(401,"GET","/api/v1/me",null,accountActive);db.update("UPDATE accounts SET active=1 WHERE account_id=201");
            String expired=login("A");((MutableClock)context.getBean(Clock.class)).value=((MutableClock)context.getBean(Clock.class)).value.plusSeconds(28800);call(401,"GET","/api/v1/me",null,expired);
            staff=identity.login("TEST-ADMIN","test-only-never-upload","员工").get("token").toString();

            String rollback=code("B");var rollbackBody=bind(rollback,3010,"0","ROLLBACK");
            fixtureAdmin.execute("CREATE TRIGGER it_fail_binding_audit BEFORE INSERT ON audit_logs FOR EACH ROW BEGIN IF NEW.reason LIKE '%ROLLBACK%' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture audit failure'; END IF; END");
            call(500,"POST",path(202)+"/binding",rollbackBody,staff);
            check(db.queryForObject("SELECT wechat_binding_generation FROM accounts WHERE account_id=202",Long.class)==0);
            check(db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE audit_id=3010",Integer.class)==0);
            fixtureAdmin.execute("DROP TRIGGER it_fail_binding_audit");call(200,"POST",path(202)+"/binding",rollbackBody,staff);

            String c1=code("C"),c2=code("C"),concurrentStaff=staff;
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                var first=pool.submit(()->mvc.perform(request(HttpMethod.POST,path(203)+"/binding").header("Authorization","Bearer "+concurrentStaff).contentType("application/json").content(JSON.writeValueAsString(bind(c1,3020,"0","TEST-C1")))).andReturn().getResponse().getStatus());
                var second=pool.submit(()->mvc.perform(request(HttpMethod.POST,path(204)+"/binding").header("Authorization","Bearer "+concurrentStaff).contentType("application/json").content(JSON.writeValueAsString(bind(c2,3021,"0","TEST-C2")))).andReturn().getResponse().getStatus());
                var statuses=new ArrayList<>(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));Collections.sort(statuses);check(statuses.equals(List.of(200,409)));
            }finally{pool.shutdownNow();}
            check(db.queryForObject("SELECT COUNT(*) FROM accounts WHERE wechat_open_id=?",Integer.class,"fixture-open-C".getBytes())==1);

            var opening=Map.of("accountId","205","patientId","505","operationId","3030","evidenceReference","TEST-OPEN","confirmedInPerson",true);
            call(200,"POST","/api/v1/staff-admin/patient-wechat/accounts",opening,staff);
            check(Boolean.TRUE.equals(call(200,"POST","/api/v1/staff-admin/patient-wechat/accounts",opening,staff).get("replayed")));
            check(!Identity.verify("test-only-never-upload",db.queryForObject("SELECT credential_ref FROM accounts WHERE account_id=205",String.class)));
            call(403,"POST","/api/v1/patient/wechat-sessions",Map.of("code","E-unbound"),null);
            call(200,"POST",path(205)+"/binding",bind(code("E"),3031,"0","TEST-OPEN-BIND"),staff);String newPatient=login("E");call(200,"GET","/api/v1/me",null,newPatient);
            check(Boolean.TRUE.equals(call(200,"GET",path(205)+"/operations/3031",null,staff).get("found")));
            for(var row:db.queryForList("SELECT before_value,after_value FROM audit_logs"))for(Object value:row.values())if(value!=null)check(!value.toString().contains("fixture-open-")&&!value.toString().contains(ticket));
            db.update("UPDATE account_grants SET active=0 WHERE account_id=101");call(403,"GET",path(201),null,staff);
            check(db.queryForObject("SELECT COUNT(*) FROM patients",Integer.class)==5);
            System.out.println("WechatDatabaseIntegrationCheck: "+checks+" passed (owned isolated MySQL + real transactions/MockMvc; fake exchange only)");
        }
    }
}
