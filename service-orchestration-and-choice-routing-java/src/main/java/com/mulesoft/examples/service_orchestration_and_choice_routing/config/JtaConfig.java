package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import com.arjuna.ats.arjuna.common.CoordinatorEnvironmentBean;
import com.arjuna.ats.arjuna.common.arjPropertyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.transaction.jta.JtaTransactionManager;

/**
 * Applies the {@code transaction-manager.*} settings to the Narayana JTA transaction manager supplied by the starter
 * (D-026).
 *
 * <p>The Narayana starter ({@code dev.snowdrop:narayana-spring-boot-starter}) defines the
 * {@link JtaTransactionManager} bean {@code transactionManager} together with the XA data source and XA connection
 * factory wrappers. That bean is the only {@code PlatformTransactionManager} of the application context: it runs the
 * XA transaction of every {@code inhouseOrder} delivery, which joins the {@code orders} insert, and of every
 * {@code audit} delivery, which joins the {@code order_audits} insert. This class declares no transaction manager,
 * user transaction or JTA {@code TransactionManager} bean of its own; it registers two post-processors that configure
 * the starter's objects from {@code application.yml}:
 * <ul>
 *   <li>{@link ArjunaReaperTimeoutInitializer} sets Arjuna's coordinator {@code txReaperTimeout}, in milliseconds,
 *       from {@code transaction-manager.tx-reaper-timeout} before any singleton bean is created and before the first
 *       transaction starts the Arjuna transaction reaper;</li>
 *   <li>{@link JtaDefaultTimeoutApplier} sets the default timeout, in seconds, of the {@code JtaTransactionManager}
 *       from {@code transaction-manager.default-timeout} before that bean is initialised.</li>
 * </ul>
 *
 * <p>Resulting state of a started context: {@code getBeansOfType(PlatformTransactionManager.class)} holds exactly one
 * entry, a {@code JtaTransactionManager} whose {@code getDefaultTimeout()} equals
 * {@code transaction-manager.default-timeout}, and
 * {@code arjPropertyManager.getCoordinatorEnvironmentBean().getTxReaperTimeout()} equals
 * {@code transaction-manager.tx-reaper-timeout}. Both values live only in {@code application.yml}. Context startup
 * fails with an {@link IllegalStateException} naming the key when a key is missing or the reaper timeout is not
 * greater than zero, and with a conversion exception when a value is not a number.
 */
@Configuration(proxyBeanMethods = false)
public class JtaConfig {

    /** Key of the default JTA transaction timeout, in seconds. */
    static final String DEFAULT_TIMEOUT_KEY = "transaction-manager.default-timeout";

    /** Key of the Arjuna transaction reaper timeout, in milliseconds. */
    static final String TX_REAPER_TIMEOUT_KEY = "transaction-manager.tx-reaper-timeout";

    /** Logger of the applied transaction-manager settings. */
    private static final Logger LOG = LoggerFactory.getLogger(JtaConfig.class);

    /**
     * Registers the bean factory post-processor that sets Arjuna's coordinator reaper timeout from
     * {@code transaction-manager.tx-reaper-timeout} (D-026).
     *
     * <p>The method is static and declares the concrete post-processor type: the container calls it without
     * instantiating {@code JtaConfig} and orders the result as {@link PriorityOrdered} among the bean factory
     * post-processors.
     *
     * @return the reaper timeout initializer; the container supplies its {@link Environment}
     */
    @Bean
    public static ArjunaReaperTimeoutInitializer arjunaReaperTimeoutInitializer() {
        return new ArjunaReaperTimeoutInitializer();
    }

    /**
     * Registers the bean post-processor that sets the default timeout of the starter's
     * {@link JtaTransactionManager} from {@code transaction-manager.default-timeout} (D-026).
     *
     * <p>The method is static and declares the concrete post-processor type: the container calls it without
     * instantiating {@code JtaConfig} and registers the result before it creates any regular bean.
     *
     * @return the default timeout applier; the container supplies its {@link Environment}
     */
    @Bean
    public static JtaDefaultTimeoutApplier jtaDefaultTimeoutApplier() {
        return new JtaDefaultTimeoutApplier();
    }

    /**
     * Returns the environment a post-processor received through {@link EnvironmentAware}.
     *
     * @param environment   the environment set on the post-processor, or {@code null} when none was set
     * @param postProcessor the simple name of the post-processor, used in the exception message
     * @return {@code environment}
     * @throws IllegalStateException when {@code environment} is {@code null}
     */
    private static Environment requireEnvironment(Environment environment, String postProcessor) {
        if (environment == null) {
            throw new IllegalStateException(
                    postProcessor + " has no Environment; setEnvironment must be called before it runs");
        }
        return environment;
    }

    /**
     * Sets {@code CoordinatorEnvironmentBean.txReaperTimeout}, in milliseconds, from
     * {@code transaction-manager.tx-reaper-timeout} when the bean factory is post-processed (D-026).
     *
     * <p>The value is applied before any singleton bean is instantiated, ahead of the first transaction, which
     * instantiates Arjuna's {@code TransactionReaper} and reads the timeout once. The post-processor runs at
     * {@link Ordered#HIGHEST_PRECEDENCE} among the {@link PriorityOrdered} bean factory post-processors.
     *
     * <p>Usage outside a container:
     * <pre>{@code
     * ArjunaReaperTimeoutInitializer initializer = new ArjunaReaperTimeoutInitializer();
     * initializer.setEnvironment(
     *         new MockEnvironment().withProperty("transaction-manager.tx-reaper-timeout", "120000"));
     * initializer.postProcessBeanFactory(new DefaultListableBeanFactory());
     * // arjPropertyManager.getCoordinatorEnvironmentBean().getTxReaperTimeout() == 120000
     * }</pre>
     */
    public static final class ArjunaReaperTimeoutInitializer
            implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

        /** Environment holding {@code transaction-manager.tx-reaper-timeout}; set by the container. */
        private Environment environment;

        /**
         * Stores the environment from which {@link #postProcessBeanFactory} reads the reaper timeout.
         *
         * @param environment the application environment
         */
        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        /**
         * Reads {@code transaction-manager.tx-reaper-timeout} as a {@code long} and passes it to
         * {@code arjPropertyManager.getCoordinatorEnvironmentBean().setTxReaperTimeout}. The bean factory itself is
         * left unchanged.
         *
         * @param beanFactory the bean factory being post-processed
         * @throws IllegalStateException when no environment was set, when the key is missing, or when its value is
         *                               not greater than zero
         * @throws org.springframework.core.convert.ConversionException when the value is not a {@code long}
         */
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // Reads the key from the Environment during bean factory post-processing, ahead of @Value and
            // @ConfigurationProperties binding (D-026).
            long txReaperTimeout = requireEnvironment(environment, getClass().getSimpleName())
                    .getRequiredProperty(TX_REAPER_TIMEOUT_KEY, Long.class);
            if (txReaperTimeout <= 0L) {
                throw new IllegalStateException(
                        "Property '" + TX_REAPER_TIMEOUT_KEY + "' must be greater than 0 milliseconds but was "
                                + txReaperTimeout);
            }
            CoordinatorEnvironmentBean coordinatorEnvironment = arjPropertyManager.getCoordinatorEnvironmentBean();
            coordinatorEnvironment.setTxReaperTimeout(txReaperTimeout);
            LOG.info("Arjuna coordinator txReaperTimeout set to {} ms from '{}' (D-026)",
                    txReaperTimeout, TX_REAPER_TIMEOUT_KEY);
        }

        /**
         * Returns {@link Ordered#HIGHEST_PRECEDENCE}.
         *
         * @return the order of this post-processor
         */
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    /**
     * Sets the default timeout, in seconds, of every {@link JtaTransactionManager} bean from
     * {@code transaction-manager.default-timeout} before the bean is initialised (D-026).
     *
     * <p>The starter's {@code transactionManager} is the only bean of that type. The timeout applies to every
     * transaction it begins without an explicit timeout of its own, the listener transactions of {@code inhouseOrder}
     * and {@code audit} included. Every other bean is returned unchanged.
     *
     * <p>Usage outside a container:
     * <pre>{@code
     * JtaDefaultTimeoutApplier applier = new JtaDefaultTimeoutApplier();
     * applier.setEnvironment(new MockEnvironment().withProperty("transaction-manager.default-timeout", "30"));
     * JtaTransactionManager manager = new JtaTransactionManager();
     * applier.postProcessBeforeInitialization(manager, "transactionManager");
     * // manager.getDefaultTimeout() == 30
     * }</pre>
     */
    public static final class JtaDefaultTimeoutApplier implements BeanPostProcessor, EnvironmentAware {

        /** Environment holding {@code transaction-manager.default-timeout}; set by the container. */
        private Environment environment;

        /**
         * Stores the environment from which {@link #postProcessBeforeInitialization} reads the default timeout.
         *
         * @param environment the application environment
         */
        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        /**
         * Calls {@code setDefaultTimeout} with {@code transaction-manager.default-timeout}, read as an {@code int},
         * when {@code bean} is a {@link JtaTransactionManager}; leaves any other bean untouched and does not read the
         * environment for it.
         *
         * @param bean     the bean instance about to be initialised
         * @param beanName the name of the bean
         * @return {@code bean}, the same instance
         * @throws IllegalStateException when {@code bean} is a {@code JtaTransactionManager} and no environment was
         *                               set or the key is missing
         * @throws org.springframework.core.convert.ConversionException when the value is not an {@code int}
         * @throws org.springframework.transaction.InvalidTimeoutException when the value is lower than {@code -1}
         */
        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof JtaTransactionManager jtaTransactionManager) {
                // Reads the key from the Environment each time a JtaTransactionManager is post-processed (D-026).
                int defaultTimeout = requireEnvironment(environment, getClass().getSimpleName())
                        .getRequiredProperty(DEFAULT_TIMEOUT_KEY, Integer.class);
                jtaTransactionManager.setDefaultTimeout(defaultTimeout);
                LOG.info("Default timeout of JTA transaction manager '{}' set to {} s from '{}' (D-026)",
                        beanName, defaultTimeout, DEFAULT_TIMEOUT_KEY);
            }
            return bean;
        }
    }
}
