package com.prismtech.vortex.flink;

import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.omg.dds.core.StatusCondition;
import org.omg.dds.core.WaitSet;
import org.omg.dds.core.policy.QosPolicy;
import org.omg.dds.core.status.DataAvailableStatus;
import org.omg.dds.core.status.Status;
import org.omg.dds.sub.DataReader;
import org.omg.dds.sub.DataReaderQos;
import org.omg.dds.topic.Topic;
import org.omg.dds.type.TypeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vortex.commons.serialization.QoSSerializer;
import vortex.commons.util.VConfig;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Objects.requireNonNull;
import static vortex.commons.util.VConfig.DefaultEntities.*;

/**
 * Created by Vortex.
 */
public class FlinkVortexReader<T>
        extends RichSourceFunction<T>
        implements ResultTypeQueryable<T>, Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(FlinkVortexReader.class);

    private static final long serialVersionUID = 304127909500L;

    private QosPolicy.ForDataReader[] qos;
    private Properties readerConfig;

    private transient DataReader<T> dr;
    private transient Topic<T> topic;

    private transient volatile boolean running = true;

    public FlinkVortexReader(Topic<T> topic, Properties readerConfig, QosPolicy.ForDataReader... qos) {
        requireNonNull(topic, "Topic not set.");
        requireNonNull(readerConfig, "Reader config not set.");

        this.topic = topic;
        this.qos = qos;
        this.readerConfig = readerConfig;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        final DataReaderQos readerQos = defaultSub().getDefaultDataReaderQos().withPolicies(qos);
        dr = defaultSub().createDataReader(topic, readerQos);
        running = true;
    }

    @Override
    public void close() throws Exception {
        super.close();

        if(dr != null) {
            dr.close();
        }
        running = false;
    }

    @Override
    public TypeInformation<T> getProducedType() {
        return TypeInformation.of(topic.getTypeSupport().getType());
    }

    @Override
    public void run(SourceContext<T> sourceContext) throws Exception {
        StatusCondition<DataReader<T>> condition = dr.getStatusCondition();
        Collection<Class<? extends Status>> statuses = new ArrayList<Class<? extends Status>>();
        statuses.add(DataAvailableStatus.class);
        condition.setEnabledStatuses(statuses);
        final WaitSet ws = VConfig.ENV.getSPI().newWaitSet();
        ws.attachCondition(condition);
        while(running) {

            try {
                ws.waitForConditions(1000, TimeUnit.MILLISECONDS);

                dr.take().forEachRemaining(sample -> {
                        final T data = sample.getData();
                        if(data != null) {
                            sourceContext.collectWithTimestamp(data, sample.getSourceTimestamp().getTime(TimeUnit.MILLISECONDS));
                        }
                    });
            } catch (TimeoutException e) {
            }
        }

    }

    @Override
    public void cancel() {
        running = false;
    }

    private void writeObject(ObjectOutputStream o)
        throws IOException {
        o.writeObject(topic.getName());
        o.writeObject(topic.getTypeSupport().getType());
        o.writeObject(topic.getTypeSupport().getTypeName());
        o.writeObject(readerConfig);
        QoSSerializer qSerializer = new QoSSerializer(defaultPolicyFactory());
        o.writeObject(qSerializer.toSerializable(qos));
    }

    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream o)
            throws IOException, ClassNotFoundException {
        String topipName = (String) o.readObject();
        Class<T> topicType = (Class<T>) o.readObject();
        String tregType = (String) o.readObject();
        readerConfig = (Properties) o.readObject();
        QoSSerializer qSerializer = new QoSSerializer(defaultPolicyFactory());
        QosPolicy[] policies = qSerializer.fromSerializable((Serializable) o.readObject());
        qos = VortexUtils.filterType(policies, QosPolicy.ForDataReader.class);
        TypeSupport<T> ts = TypeSupport.newTypeSupport(topicType, tregType, VConfig.ENV);
        topic = defaultDomainParticipant().createTopic(topipName, ts);
    }

}
