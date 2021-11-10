### Sharding-JDBC源码学习

#### 1. Sharding-JDBC的初始化

​		sharding-jdbc的初始化是通过工厂类ShardingDataSourceFactory创建的，涉及参数包括

- Map<Strinf, DataSource>  数据源集合
- ShardingRuleConfiguration  分库分表规则配置文件
- Properties  sharding-jdbc的配置文件，如sql.show : true，表示控制台日志打印具体执行的sql

```java
   public final class ShardingDataSourceFactory {
       
       /**
        * Create sharding data source.
        *
        * @param dataSourceMap data source map
        * @param shardingRuleConfig rule configuration for databases and tables sharding
        * @param props properties for data source
        * @return sharding data source
        * @throws SQLException SQL exception
        */
       public static DataSource createDataSource(
               final Map<String, DataSource> dataSourceMap, final ShardingRuleConfiguration shardingRuleConfig, final Properties props) throws SQLException {
           return new ShardingDataSource(dataSourceMap, new ShardingRule(shardingRuleConfig, dataSourceMap.keySet()), props);
       }
   }
```

ShardingDataSource的初始化的作用主要包括两方面
- 数据源元数据信息的收集和表元数据的收集
- 分库分表的策略和算法配置收集

通过将分库分表配置ShardingRuleConfiguration解析后转化为具体的规则类ShardingRule，再通过数据源和规则类完成 sharding-jdbc的上下文ShardingRuntimeContext的初始化

```java
public final class ShardingRuntimeContext extends MultipleDataSourcesRuntimeContext<ShardingRule> {
    
    private final CachedDatabaseMetaData cachedDatabaseMetaData;
    
    private final ShardingTransactionManagerEngine shardingTransactionManagerEngine;
    
    //初始化上下文的构造方法
    public ShardingRuntimeContext(final Map<String, DataSource> dataSourceMap, final ShardingRule shardingRule, final Properties props, final DatabaseType databaseType) throws SQLException {
        //在其父类AbstractRuntimeContext中初始化，包括SQL执行引擎ExecutorEngine和SQL解析引擎SQLParserEngine的初始化
        super(dataSourceMap, shardingRule, props, databaseType);
        //定义上下文的元数据
        cachedDatabaseMetaData = createCachedDatabaseMetaData(dataSourceMap);
        //创建事务引擎
        shardingTransactionManagerEngine = new ShardingTransactionManagerEngine();
        //事务引擎的初始化，databaseType表示数据库的具体类型，Oracle Mysql等等，用于后期sql解析库的规则匹配
        shardingTransactionManagerEngine.init(databaseType, dataSourceMap);
    }

    //上下文元数据的初始化，遍历数据源获取连接，通过数据源连接获取数据库元数据
   private CachedDatabaseMetaData createCachedDatabaseMetaData(final Map<String, DataSource> dataSourceMap) throws SQLException {
        try (Connection connection = dataSourceMap.values().iterator().next().getConnection()) {
            return new CachedDatabaseMetaData(connection.getMetaData());
        }
    }

}
```
```java
public abstract class AbstractRuntimeContext<T extends BaseRule> implements RuntimeContext<T> {
    
    private final T rule;
    
    private final ConfigurationProperties properties;
    
    private final DatabaseType databaseType;
    
    private final ExecutorEngine executorEngine;
    
    private final SQLParserEngine sqlParserEngine;
    
    //ShardingRuntimeContext的抽象父类的初始化
    protected AbstractRuntimeContext(final T rule, final Properties props, final DatabaseType databaseType) {
        this.rule = rule;
        properties = new ConfigurationProperties(null == props ? new Properties() : props);
        this.databaseType = databaseType;
        //初始化SQL的执行引擎
        executorEngine = new ExecutorEngine(properties.<Integer>getValue(ConfigurationPropertyKey.EXECUTOR_SIZE));
        //根据不同的数据库类型DatabaseType通过SQL解析工厂获取对应的sql解析引擎
        sqlParserEngine = SQLParserEngineFactory.getSQLParserEngine(DatabaseTypes.getTrunkDatabaseTypeName(databaseType));
        ConfigurationLogger.log(rule.getRuleConfiguration());
        ConfigurationLogger.log(props);
    }
    
    protected abstract ShardingSphereMetaData getMetaData();
    
    @Override
    public void close() throws Exception {
        executorEngine.close();
    }
}
```

   总结来说，ShardingDataSource的初始化，在收集了数据库的元数据和分库分表规则后，通过上下文的初始化，又完成了事务引擎、SQL执行引擎、SQL解析引擎的初始化以及分库分表规则的加载。
   
### 2. Sharding-JDBC的配置初始化与解析
    
  ShardingRuleConfiguration是分库分表配置的核心和入口，其内部包含两个核心链表
- Collection<TableRuleConfiguration> tableRuleConfigs
- Collection<MasterSlaveRuleConfiguration> masterSlaveRuleConfigs

   每一组相同规则分片的表配置一个TableRuleConfiguration,其代表一个表或库的分库分表策略配置，其内部包含两个类型为ShardingStrategyConfiguration的属性 
```java
    public final class TableRuleConfiguration {
        
        //逻辑表名，如 user
        private final String logicTable;
        
        // 实际节点，如 relay.user_$->{0..63}
        private final String actualDataNodes;
        
        //库分表策略配置
        private ShardingStrategyConfiguration databaseShardingStrategyConfig;
        
        //表分表策略配置
        private ShardingStrategyConfiguration tableShardingStrategyConfig;
        
        //设置键配置
        private KeyGeneratorConfiguration keyGeneratorConfig;
        
        public TableRuleConfiguration(final String logicTable) {
            this(logicTable, null);
        }
        
        public TableRuleConfiguration(final String logicTable, final String actualDataNodes) {
            Preconditions.checkArgument(!Strings.isNullOrEmpty(logicTable), "LogicTable is required.");
            this.logicTable = logicTable;
            this.actualDataNodes = actualDataNodes;
        }
    }
```
针对ShardingStrategyConfiguration有四种实现类

- StandardShardingStrategyConfiguration  支持精确分片和范围分片
- ComplexShardingStrategyConfiguration  支持复杂分表
- HintShardingStrategyConfiguration  强制某种策略分片
- InlineShardingStrategyConfiguration  支持表达式分片

针对以上分片策略都关联到一到两个对应的分片算法，分片算法都由接口ShardingAlgorithm表示，抽象子类包括

- PreciseShardingAlgorithm 精确分片算法
- RangeShardingAlgorithm  范围分片算法
- HintShardingAlgorithm  强制分片算法
- ComplexKeysShardingAlgorithm  复杂分片算法

如比较常用的StandardShardingStrategyConfiguration源码如下：

```java
    public final class StandardShardingStrategyConfiguration implements ShardingStrategyConfiguration {
        
        private final String shardingColumn;
        
        //精确分片算法
        private final PreciseShardingAlgorithm preciseShardingAlgorithm;
        //范围分片算法
        private final RangeShardingAlgorithm rangeShardingAlgorithm;
        //初始化分片策略，指定分片字段
        public StandardShardingStrategyConfiguration(final String shardingColumn, final PreciseShardingAlgorithm preciseShardingAlgorithm) {
            this(shardingColumn, preciseShardingAlgorithm, null);
        }
        
        public StandardShardingStrategyConfiguration(final String shardingColumn, final PreciseShardingAlgorithm preciseShardingAlgorithm, final RangeShardingAlgorithm rangeShardingAlgorithm) {
            Preconditions.checkArgument(!Strings.isNullOrEmpty(shardingColumn), "ShardingColumns is required.");
            Preconditions.checkNotNull(preciseShardingAlgorithm, "PreciseShardingAlgorithm is required.");
            this.shardingColumn = shardingColumn;
            this.preciseShardingAlgorithm = preciseShardingAlgorithm;
            this.rangeShardingAlgorithm = rangeShardingAlgorithm;
        }
    }
```

具体的分片算法可通过实现分片算法实现，实现doSharding方法,以具体的User表分片算法为例
```java
    public class MyTablePreciseShardingAlgorithm implements PreciseShardingAlgorithm<String> {
    
        @Override
        public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<String> shardingValue) {
            log.info("collection:" + JSONObject.toJSONString(availableTargetNames) + ",preciseShardingValue:" + JSONObject.toJSONString(shardingValue));
            //preciseShardingValue就是当前插入的字段值
            //collection 内就是所有的逻辑表
            //获取字段值
            String username = shardingValue.getValue();
    
            if(username == null){
                log.error("分表健username为null");
                throw new UnsupportedOperationException("username is null");
            }
            //循环表名已确定使用哪张表  64
            return availableTargetNames.stream().filter(tableName -> {
                //user_0,user_extend_0
                String num = CharMatcher.inRange('0', '9').negate().removeFrom(tableName);
                int hashNum = username.hashCode();
                if (hashNum == Integer.MIN_VALUE) {
                    hashNum++;
                }
                int shardNum = Math.abs(hashNum) % 64;
                return StringUtils.equals(num, String.valueOf(shardNum));
            }).findFirst().orElse(null);
        }
    }
```

在分表分库的配置规则ShardingRuleConfiguration作为参数被用来初始化Sharding-JDBC的过程中，其配置会被转化为具体的ShardingRule对象
```java
    public final class ShardingDataSourceFactory {
        
        /**
         * Create sharding data source.
         *
         * @param dataSourceMap data source map
         * @param shardingRuleConfig rule configuration for databases and tables sharding
         * @param props properties for data source
         * @return sharding data source
         * @throws SQLException SQL exception
         */
        public static DataSource createDataSource(
                final Map<String, DataSource> dataSourceMap, final ShardingRuleConfiguration shardingRuleConfig, final Properties props) throws SQLException {
           //通过ShardingRuleConfiguration构造对应的ShardingRule对象
            return new ShardingDataSource(dataSourceMap, new ShardingRule(shardingRuleConfig, dataSourceMap.keySet()), props);
        }
    }
```
shardingRuleConfiguration会在ShardingRule实例化时在构造方法内被遍历，将其配置的规则抽取，实例化为具体的TableRule对象。

```java
    public class ShardingRule implements BaseRule {
           //构造器实例化ShardingRule对象
           public ShardingRule(final ShardingRuleConfiguration shardingRuleConfig, final Collection<String> dataSourceNames) {
                  Preconditions.checkArgument(null != shardingRuleConfig, "ShardingRuleConfig cannot be null.");
                  Preconditions.checkArgument(null != dataSourceNames && !dataSourceNames.isEmpty(), "Data sources cannot be empty.");
                  this.ruleConfiguration = shardingRuleConfig;
                  shardingDataSourceNames = new ShardingDataSourceNames(shardingRuleConfig, dataSourceNames);
                  //通过遍历配置文件，将针对单表单库的配置进行抽取，实例化为TableRule的集合
                  tableRules = createTableRules(shardingRuleConfig);
                  broadcastTables = shardingRuleConfig.getBroadcastTables();
                  bindingTableRules = createBindingTableRules(shardingRuleConfig.getBindingTableGroups());
                  defaultDatabaseShardingStrategy = createDefaultShardingStrategy(shardingRuleConfig.getDefaultDatabaseShardingStrategyConfig());
                  defaultTableShardingStrategy = createDefaultShardingStrategy(shardingRuleConfig.getDefaultTableShardingStrategyConfig());
                  defaultShardingKeyGenerator = createDefaultKeyGenerator(shardingRuleConfig.getDefaultKeyGeneratorConfig());
                  masterSlaveRules = createMasterSlaveRules(shardingRuleConfig.getMasterSlaveRuleConfigs());
                  encryptRule = createEncryptRule(shardingRuleConfig.getEncryptRuleConfig());
              }
        
          private Collection<TableRule> createTableRules(final ShardingRuleConfiguration shardingRuleConfig) {
                return shardingRuleConfig.getTableRuleConfigs().stream().map(each ->
                        new TableRule(each, shardingDataSourceNames, getDefaultGenerateKeyColumn(shardingRuleConfig))).collect(Collectors.toList());
            }    
    }
```

一个TableRule代表了一个逻辑表的库表资源,内部维护一个DataNode的集合属性actualDataNodes，表示所有针对该逻辑表的对应的实际库表的集合，后续Sharding-JDBC做路由时是根据此集合和使用相应的算法进行实际库表的选取。

```java
    public final class TableRule {
        //逻辑表名
        private final String logicTable;
        //实际库表的集合
        private final List<DataNode> actualDataNodes;
        //实际库表的名称集合    
        @Getter(AccessLevel.NONE)
        private final Set<String> actualTables;
        
        @Getter(AccessLevel.NONE)
        private final Map<DataNode, Integer> dataNodeIndexMap;
        //数据库分表策略    
        private final ShardingStrategy databaseShardingStrategy;
        //表分表策略    
        private final ShardingStrategy tableShardingStrategy;
            
        @Getter(AccessLevel.NONE)
        private final String generateKeyColumn;
            
        private final ShardingKeyGenerator shardingKeyGenerator;
        //实际数据源名称集合    
        private final Collection<String> actualDatasourceNames = new LinkedHashSet<>();
            
        private final Map<String, Collection<String>> datasourceToTablesMap = new HashMap<>();

    }
```

### 3.sharding-JDBC的SQL路由与解析


Sharding-JDBC的SQL执行的核心类是ShardingPreparedStatement.

#### 3.1 ShardingPreparedStatement的初始化

ShardingPreparedStatement实现了PrepareStatement接口

ShardingConnection实现了Connection接口

ShardingConnection接口的初始化发生在ShardDataSource的初始化阶段
```java
public class ShardingDataSource extends AbstractDataSourceAdapter {
     @Override
     public final ShardingConnection getConnection() {
         return new ShardingConnection(getDataSourceMap(), runtimeContext, TransactionTypeHolder.get());
     }
}
```
ShardingPreparedStatement的初始化则是通过Connection实例获取
```java
public final class ShardingConnection extends AbstractConnectionAdapter {
        @Override
        public PreparedStatement prepareStatement(final String sql) throws SQLException {
            return new ShardingPreparedStatement(this, sql);
        }
        
        @Override
        public PreparedStatement prepareStatement(final String sql, final int resultSetType, final int resultSetConcurrency) throws SQLException {
            return new ShardingPreparedStatement(this, sql, resultSetType, resultSetConcurrency);
        }
        
        @Override
        public PreparedStatement prepareStatement(final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) throws SQLException {
            return new ShardingPreparedStatement(this, sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }
        
        @Override
        public PreparedStatement prepareStatement(final String sql, final int autoGeneratedKeys) throws SQLException {
            return new ShardingPreparedStatement(this, sql, autoGeneratedKeys);
        }
        
        @Override
        public PreparedStatement prepareStatement(final String sql, final int[] columnIndexes) throws SQLException {
            return new ShardingPreparedStatement(this, sql, Statement.RETURN_GENERATED_KEYS);
        }
        
        @Override
        public PreparedStatement prepareStatement(final String sql, final String[] columnNames) throws SQLException {
            return new ShardingPreparedStatement(this, sql, Statement.RETURN_GENERATED_KEYS);
        }    
}

```

在ShardingPreparedStatement初始化过程中，在其构造方法内构造了四个核心实例

- ShardingParameterMetaData 
- PreparedQueryPrepareEngine 
- PreparedStatementExecutor
- BatchPreparedStatementExecutor

```java
public final class ShardingPreparedStatement extends AbstractShardingPreparedStatementAdapter {
        private ShardingPreparedStatement(final ShardingConnection connection, final String sql,
                                          final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability, final boolean returnGeneratedKeys) throws SQLException {
            if (Strings.isNullOrEmpty(sql)) {
                throw new SQLException(SQLExceptionConstant.SQL_STRING_NULL_OR_EMPTY);
            }
            this.connection = connection;
            this.sql = sql;
            ShardingRuntimeContext runtimeContext = connection.getRuntimeContext();
            parameterMetaData = new ShardingParameterMetaData(runtimeContext.getSqlParserEngine(), sql);
            prepareEngine = new PreparedQueryPrepareEngine(runtimeContext.getRule().toRules(), runtimeContext.getProperties(), runtimeContext.getMetaData(), runtimeContext.getSqlParserEngine());
            preparedStatementExecutor = new PreparedStatementExecutor(resultSetType, resultSetConcurrency, resultSetHoldability, returnGeneratedKeys, connection);
            batchPreparedStatementExecutor = new BatchPreparedStatementExecutor(resultSetType, resultSetConcurrency, resultSetHoldability, returnGeneratedKeys, connection);
        }    
}
```

##### 3.1.1  ShardingParameterMetaData

ShardingParameterMetaData仅实现了ParameterMetaData的getParameterCount方法，通过解析引擎SQLParserEngine获取到参数的数量。

```java
public final class ShardingParameterMetaData extends AbstractUnsupportedOperationParameterMetaData {
    
    private final SQLParserEngine sqlParserEngine;
    
    private final String sql;
    
    @Override
    public int getParameterCount() {
        return sqlParserEngine.parse(sql, true).getParameterCount();
    }
}
```

##### 3.1.2 PreparedQueryPrepareEngine

PreparedQueryPrepareEngine是SQL查询准备引擎的核心接口，其构造方法接受四个参数

-  Collection<BaseRule> rules 即ShardingRule的集合
-  ConfigurationProperties jdbc本身的配置文件
-  ShardingSphereMetaData 
-  SQLParserEngine 即SQL解析器

其内部核心方法则是route方法，实现sql的路由的配置入口
其父类抽象还有一个实现类为SimpleQueryPrepareEngine（后续补充说明）

```java
public final class PreparedQueryPrepareEngine extends BasePrepareEngine {
    
    public PreparedQueryPrepareEngine(final Collection<BaseRule> rules, final ConfigurationProperties properties, final ShardingSphereMetaData metaData, final SQLParserEngine sqlParserEngine) {
        super(rules, properties, metaData, sqlParserEngine);
    }
    
    @Override
    protected List<Object> cloneParameters(final List<Object> parameters) {
        return new ArrayList<>(parameters);
    }
    
    @Override
    protected RouteContext route(final DataNodeRouter dataNodeRouter, final String sql, final List<Object> parameters) {
        return dataNodeRouter.route(sql, parameters, true);
    }
}
```

由代码中可以看到PreparedQueryPrepareEngine的父类BasePrepareEngine也完成了初始化，PreparedQueryPrepareEngine的route方法的调用发生在其父类的方法中，DataNodeRouter的实例化也是发生在父类中
```java
public abstract class BasePrepareEngine {
       public BasePrepareEngine(final Collection<BaseRule> rules, final ConfigurationProperties properties, final ShardingSphereMetaData metaData, final SQLParserEngine parser) {
            this.rules = rules;
            this.properties = properties;
            this.metaData = metaData;
            router = new DataNodeRouter(metaData, properties, parser);
            rewriter = new SQLRewriteEntry(metaData.getSchema(), properties);
        }   

        private RouteContext executeRoute(final String sql, final List<Object> clonedParameters) {
            registerRouteDecorator();
            //该route方法调用的为子类的具体实现类
            return route(router, sql, clonedParameters);
        }
        //PreparedQueryPrepareEngine实现了该方法
        protected abstract RouteContext route(DataNodeRouter dataNodeRouter, String sql, List<Object> parameters);
}
```

所以 DataNodeRouter的实例调用route方法将会开启sql的路由处理。
DataNodeRouter的实例化来自于BasePrepareEngine，BasePrepareEngine的实例化来自于PreparedQueryPrepareEngine，PreparedQueryPrepareEngine的实例化则来自于ShardingPreparedStatement，依次追踪向上，源于ShardingDataSource的实例化。

DataNodeRouter的核心方法为route方法。
通过在BasePrepareEngine类中初始化DataNodeRouter实例，将SQL解析器进行传递，SQL解析器在DataNodeRouter实例中执行，生成SQLStatement，SQLStatementContextFactory通过工厂方法获取SQLStatement上下文，再实例化RouteContext对象

```java
public final class DataNodeRouter {
    
    private final ShardingSphereMetaData metaData;
    
    private final ConfigurationProperties properties;
    
    private final SQLParserEngine parserEngine;
    
    private final Map<BaseRule, RouteDecorator> decorators = new LinkedHashMap<>();
    
    private SPIRoutingHook routingHook = new SPIRoutingHook();
    
    /**
     * Register route decorator.
     *
     * @param rule rule
     * @param decorator route decorator
     */
    public void registerDecorator(final BaseRule rule, final RouteDecorator decorator) {
        decorators.put(rule, decorator);
    }
    
    /**
     * Route SQL.
     *
     * @param sql SQL
     * @param parameters SQL parameters
     * @param useCache whether cache SQL parse result
     * @return route context
     */
    public RouteContext route(final String sql, final List<Object> parameters, final boolean useCache) {
        routingHook.start(sql);
        try {
            RouteContext result = executeRoute(sql, parameters, useCache);
            routingHook.finishSuccess(result, metaData.getSchema());
            return result;
            // CHECKSTYLE:OFF
        } catch (final Exception ex) {
            // CHECKSTYLE:ON
            routingHook.finishFailure(ex);
            throw ex;
        }
    }
    
    @SuppressWarnings("unchecked")
    private RouteContext executeRoute(final String sql, final List<Object> parameters, final boolean useCache) {
        RouteContext result = createRouteContext(sql, parameters, useCache);
        for (Entry<BaseRule, RouteDecorator> entry : decorators.entrySet()) {
            result = entry.getValue().decorate(result, metaData, entry.getKey(), properties);
        }
        return result;
    }
    
    private RouteContext createRouteContext(final String sql, final List<Object> parameters, final boolean useCache) {
        SQLStatement sqlStatement = parserEngine.parse(sql, useCache);
        try {
            SQLStatementContext sqlStatementContext = SQLStatementContextFactory.newInstance(metaData.getSchema(), sql, parameters, sqlStatement);
            return new RouteContext(sqlStatementContext, parameters, new RouteResult());
            // TODO should pass parameters for master-slave
        } catch (final IndexOutOfBoundsException ex) {
            return new RouteContext(new CommonSQLStatementContext(sqlStatement), parameters, new RouteResult());
        }
    }
}
```


另外BasePrepareEngine中有一个核心方法prepare()，该方法用于对sql进行路由解析，该方法通过解析BaseRule的类型，创建对应的RouteDecorator实现类实例，并将该实例routeDecorator和ruler注册进DataNodeRouter实例中去，DataNodeRouter调用自身route方法对sql进行路由解析，生成RouterContext上下文
BaseRule为规则rule的上层统一接口，其实现类如下：

- EncryptRule
- MasterSlaveRule
- ShadowRule
- shardingRule
```java
    public interface RouteDecorator<T extends BaseRule> extends OrderAware<Class<T>> {
        
        /**
         * Decorate route context.
         * 
         * @param routeContext route context
         * @param metaData meta data of ShardingSphere
         * @param rule rule
         * @param properties configuration properties
         * @return decorated route context
         */
        RouteContext decorate(RouteContext routeContext, ShardingSphereMetaData metaData, T rule, ConfigurationProperties properties);
    }
```

同样RouterDecorator也同样为上层统一接口，其实现类如下：

- MasterSlaveRouterDecorator
- ShardingRouteDecorator

由此可以理解，代码将会通过具体的BaseRule实现类来决定具体的RouterDecorator实现类

```java
public abstract class BasePrepareEngine {

      public ExecutionContext prepare(final String sql, final List<Object> parameters) {
            //复制sql查询参数
            List<Object> clonedParameters = cloneParameters(parameters);
            //DataNodeRouter实例装配具体的路由装饰器，SQL,参数等等
            RouteContext routeContext = executeRoute(sql, clonedParameters);
            //使用SqlStatement初始化执行上下文ExecutionContext
            ExecutionContext result = new ExecutionContext(routeContext.getSqlStatementContext());
            //待解释
            result.getExecutionUnits().addAll(executeRewrite(sql, clonedParameters, routeContext));
            if (properties.<Boolean>getValue(ConfigurationPropertyKey.SQL_SHOW)) {
                SQLLogger.logSQL(sql, properties.<Boolean>getValue(ConfigurationPropertyKey.SQL_SIMPLE), result.getSqlStatementContext(), result.getExecutionUnits());
            }
            return result;
        }
        protected abstract List<Object> cloneParameters(List<Object> parameters);
            
        private RouteContext executeRoute(final String sql, final List<Object> clonedParameters) {
            registerRouteDecorator();
            //DataNodeRouter调用自身route方法，开进行路由。
            return route(router, sql, clonedParameters);
        }
        
        //实例化RouteDecorator实例，根据具体的BaseRule实例类型，注册对应的RouteDecorator实例和BaseRule实例到DataNodeRouter实例中去
        private void registerRouteDecorator() {
            for (Class<? extends RouteDecorator> each : OrderedRegistry.getRegisteredClasses(RouteDecorator.class)) {
                RouteDecorator routeDecorator = createRouteDecorator(each);
                Class<?> ruleClass = (Class<?>) routeDecorator.getType();
                // FIXME rule.getClass().getSuperclass() == ruleClass for orchestration, should decouple extend between orchestration rule and sharding rule
                rules.stream().filter(rule -> rule.getClass() == ruleClass || rule.getClass().getSuperclass() == ruleClass).collect(Collectors.toList())
                        .forEach(rule -> router.registerDecorator(rule, routeDecorator));
            }
        }
        
        //RouteDecorator实例化
        private RouteDecorator createRouteDecorator(final Class<? extends RouteDecorator> decorator) {
            try {
                return decorator.newInstance();
            } catch (final InstantiationException | IllegalAccessException ex) {
                throw new ShardingSphereException(String.format("Can not find public default constructor for route decorator `%s`", decorator), ex);
            }
        }
      
    
}
```

##### 3.1.3 PreparedStatementExecutor

PreparedStatementExecutor类主要用于具体的SQL执行

其实例化同样也发生在ShardingPreparedStatement初始化过程中，通过自身实例化和父类AbstractStatementExecutor的实例化，通过数据库连接ShardingConnection获取上下文ShardingRuntimeContext，再从上线文获取加载涉及SQL执行的相关配置
如数据库类型databaseType、结果集类型resultSetType，sql执行引擎executorEngine、以及读取配置文件中单次查询最大连接数maxConnectionsSizePerQuery等等

```java
public final class PreparedStatementExecutor extends AbstractStatementExecutor {
      public PreparedStatementExecutor(
                final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability, final boolean returnGeneratedKeys, final ShardingConnection shardingConnection) {
            super(resultSetType, resultSetConcurrency, resultSetHoldability, shardingConnection);
            this.returnGeneratedKeys = returnGeneratedKeys;
        }
}
```

```java
public abstract class AbstractStatementExecutor {
      public AbstractStatementExecutor(final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability, final ShardingConnection shardingConnection) {
            //数据库类型
            this.databaseType = shardingConnection.getRuntimeContext().getDatabaseType();
            //结果集类型
            this.resultSetType = resultSetType;
            this.resultSetConcurrency = resultSetConcurrency;
            this.resultSetHoldability = resultSetHoldability;
            //数据库连接
            this.connection = shardingConnection;
            //单词查询最大连接数
            int maxConnectionsSizePerQuery = connection.getRuntimeContext().getProperties().<Integer>getValue(ConfigurationPropertyKey.MAX_CONNECTIONS_SIZE_PER_QUERY);
            //sql执行引擎
            ExecutorEngine executorEngine = connection.getRuntimeContext().getExecutorEngine();
            //待解释
            sqlExecutePrepareTemplate = new SQLExecutePrepareTemplate(maxConnectionsSizePerQuery);
            //待解释
            sqlExecuteTemplate = new SQLExecuteTemplate(executorEngine, connection.isHoldTransaction());
        }    
}
```
以上初始化配置将会在PreparedStatementExecutor实例执行sql查询时用到。


##### 3.1.4 BatchPreparedStatementExecutor

BatchPreparedStatementExecutor 是批量SQL执行器，其父抽象类也是AbstractStatementExecutor,其自身初始化内容和PreparedStatementExecutor初始化相同，区别在于不同Executor的执行方法不同

AbstractStatementExecutor抽象有三个具体的实现类：

- PreparedStatementExecutor
- StatementExecutor
- BatchPreparedStatementExecutor


现针对不同的具体查询或更新方法做一一分析

##### 3.2 ShardingPreparedStatement的SQL查询（executeQuery()）

因为ShardingPreparedStatement实现了PreparedStatement接口，所以Sharding-jdbc执行sql的总的始发点就在这里，其实现PreparedStatement的方法涉及SQL查询、SQL更新和结果集合的获取等等。
```java
public final class ShardingPreparedStatement extends AbstractShardingPreparedStatementAdapter {
        @Override
        public ResultSet executeQuery() throws SQLException {
            ResultSet result;
            try {
                clearPrevious();
                prepare();
                initPreparedStatementExecutor();
                MergedResult mergedResult = mergeQuery(preparedStatementExecutor.executeQuery());
                result = new ShardingResultSet(preparedStatementExecutor.getResultSets(), mergedResult, this, executionContext);
            } finally {
                clearBatch();
            }
            currentResultSet = result;
            return result;
        }


        @Override
        public int executeUpdate() throws SQLException {
            try {
                clearPrevious();
                prepare();
                initPreparedStatementExecutor();
                return preparedStatementExecutor.executeUpdate();
            } finally {
                clearBatch();
            }
        }

        
        @Override
        public boolean execute() throws SQLException {
            try {
                clearPrevious();
                prepare();
                initPreparedStatementExecutor();
                return preparedStatementExecutor.execute();
            } finally {
                clearBatch();
            }
        }
}
```

##### 3.2.1  clearPrevious()

该方法具体内容如下：
```java
public final class ShardingPreparedStatement extends AbstractShardingPreparedStatementAdapter {
     private void clearPrevious() throws SQLException {
                preparedStatementExecutor.clear();
     }
}
```

其调用则是具体SQL执行器PreparedStatementExecutor的抽象父类AbstractStatementExecutor的方法，用于进行当前线程涉及SQL查询的属性清空重置
```java
public abstract class AbstractStatementExecutor {
    
    public void clear() throws SQLException {
            clearStatements();
            statements.clear();
            parameterSets.clear();
            connections.clear();
            resultSets.clear();
            inputGroups.clear();
        }
}
```
##### 3.2.2 prepare()

```java
public final class ShardingPreparedStatement extends AbstractShardingPreparedStatementAdapter {
       private void prepare() {
             executionContext = prepareEngine.prepare(sql, getParameters());
             findGeneratedKey().ifPresent(generatedKey -> generatedValues.add(generatedKey.getGeneratedValues().getLast()));
       }
}
```
通过执行SQL查询准备引擎PreparedQueryPrepareEngine的父类BasePrepareEngine的prepare方法获取执行器的上下文ExecutionContext

##### 3.2.3 initPreparedStatementExecutor()

通过prepare()获得的ExecutionContext将会被用来作为PreparedStatementExecutor的参数配置

PreparedStatementExecutor实例在调用自身方法executeQuery()完成查询后将结果返回，通过mergeQuery实现结果聚合
最终生成对应的查询结果集ShardingResultSet的实例返回，ShardingResultSet实现了ResultSet接口