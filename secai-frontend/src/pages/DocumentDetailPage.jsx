import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Card, Tag, Button, Typography, Alert, Spin,
    Descriptions, Row, Col, Statistic, Empty, List, Space, Divider,
} from 'antd';
import {
    ArrowLeftOutlined, CheckCircleOutlined,
    ExclamationCircleOutlined, LoadingOutlined,
    DatabaseOutlined, FileTextOutlined,
    QuestionCircleOutlined, UploadOutlined,
    CalendarOutlined,
} from '@ant-design/icons';

import AppLayout from '../components/AppLayout';
import { getDocument } from '../api/documents';
import { useDocumentPoller } from '../hooks/useDocumentPoller';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';

dayjs.extend(relativeTime);

const { Title, Text, Paragraph } = Typography;

const STATUS_COLOR = {
    PENDING:    'default',
    PROCESSING: 'processing',
    READY:      'success',
    FAILED:     'error',
};

const STATUS_LABELS = {
    PENDING:    'Pending',
    PROCESSING: 'Processing',
    READY:      'Ready',
    FAILED:     'Failed',
};

// ─── Questions answered using this doc (if API provides them) ─────────────────
// If your API returns `questionsAnswered` on the document object, this renders them.
// Otherwise shows a helpful placeholder guiding to the questionnaire page.
function QuestionsAnsweredSection({ doc }) {
    const navigate = useNavigate();
    const hasData  = doc.questionsAnswered && doc.questionsAnswered.length > 0;

    if (!hasData) {
        return (
            <div style={{
                padding:      '20px 16px',
                textAlign:    'center',
                background:   '#fafafa',
                borderRadius: 8,
                border:       '1px dashed #d9d9d9',
            }}>
                <QuestionCircleOutlined style={{ fontSize: 28, color: '#d9d9d9', marginBottom: 8, display: 'block' }} />
                <Text type="secondary" style={{ fontSize: 13 }}>
                    Upload a questionnaire to see which questions this document helps answer.
                </Text>
                <div style={{ marginTop: 12 }}>
                    <Button
                        size="small"
                        onClick={() => navigate('/questionnaires/upload')}
                        icon={<UploadOutlined />}
                    >
                        Upload questionnaire
                    </Button>
                </div>
            </div>
        );
    }

    return (
        <List
            size="small"
            dataSource={doc.questionsAnswered}
            renderItem={(item) => (
                <List.Item style={{ padding: '8px 0', borderBottom: '1px solid #f5f5f5' }}>
                    <Space direction="vertical" size={2} style={{ width: '100%' }}>
                        <Text style={{ fontSize: 13, fontWeight: 500 }}>{item.questionText}</Text>
                        {item.answerSnippet && (
                            <Text type="secondary" style={{ fontSize: 12, fontStyle: 'italic' }}>
                                "{item.answerSnippet}"
                            </Text>
                        )}
                        {item.confidence != null && (
                            <Text style={{ fontSize: 11, color: item.confidence >= 0.85 ? '#52c41a' : item.confidence >= 0.60 ? '#faad14' : '#ff4d4f' }}>
                                {Math.round(item.confidence * 100)}% confidence
                            </Text>
                        )}
                    </Space>
                </List.Item>
            )}
        />
    );
}

export default function DocumentDetailPage() {
    const { id }        = useParams();
    const navigate      = useNavigate();
    const [doc, setDoc] = useState(null);
    const [loading, setLoading]   = useState(true);
    const [pageError, setPageError] = useState('');

    useEffect(() => {
        (async () => {
            try {
                const res = await getDocument(id);
                setDoc(res.data);
            } catch {
                setPageError('Document not found or you do not have access.');
            } finally {
                setLoading(false);
            }
        })();
    }, [id]);

    const shouldPoll = doc && (doc.status === 'PENDING' || doc.status === 'PROCESSING');

    const { status: liveStatus, chunkCount, errorMessage: liveError } =
        useDocumentPoller(shouldPoll ? id : null, (finalData) => {
            setDoc((prev) => ({
                ...prev,
                status:       finalData.status,
                errorMessage: finalData.errorMessage,
            }));
        });

    const currentStatus = (shouldPoll && liveStatus) ? liveStatus : doc?.status;

    if (loading) {
        return (
            <AppLayout>
                <div style={{ textAlign: 'center', padding: 80 }}>
                    <Spin size="large" />
                </div>
            </AppLayout>
        );
    }

    if (pageError) {
        return (
            <AppLayout>
                <div style={{ padding: 24 }}>
                    <Alert type="error" message={pageError} showIcon />
                    <Button icon={<ArrowLeftOutlined />} style={{ marginTop: 16 }}
                            onClick={() => navigate('/documents')}>
                        Back to Documents
                    </Button>
                </div>
            </AppLayout>
        );
    }

    const isReady    = currentStatus === 'READY';
    const isFailed   = currentStatus === 'FAILED';
    const isInFlight = currentStatus === 'PENDING' || currentStatus === 'PROCESSING';
    const finalChunkCount = chunkCount ?? doc?.chunkCount ?? null;

    return (
        <AppLayout>
            <div style={{ padding: 24, maxWidth: 860 }}>

                {/* Back + header */}
                <div style={{ marginBottom: 20 }}>
                    <Button type="text" icon={<ArrowLeftOutlined />}
                            onClick={() => navigate('/documents')}
                            style={{ paddingLeft: 0, marginBottom: 8 }}>
                        All Documents
                    </Button>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                        <FileTextOutlined style={{ fontSize: 22, color: '#1890ff' }} />
                        <Title level={4} style={{ margin: 0 }}>{doc.filename}</Title>
                        <Tag color={STATUS_COLOR[currentStatus]}>
                            {STATUS_LABELS[currentStatus] ?? currentStatus}
                        </Tag>
                    </div>
                    <Text type="secondary" style={{ fontSize: 13, marginTop: 4, display: 'block' }}>
                        Uploaded {dayjs(doc.uploadedAt).fromNow()}
                        {doc.processedAt && ` · Indexed ${dayjs(doc.processedAt).fromNow()}`}
                    </Text>
                </div>

                {/* Status alerts — business-friendly language */}
                {isInFlight && (
                    <Alert
                        type="info"
                        showIcon
                        icon={<LoadingOutlined />}
                        message="Indexing in progress"
                        description="We're reading and indexing this document so the AI can use it to answer your security questionnaires. This usually takes under a minute."
                        style={{ marginBottom: 16 }}
                    />
                )}

                {isReady && (
                    <Alert
                        type="success"
                        showIcon
                        icon={<CheckCircleOutlined />}
                        message="Document is ready"
                        description={
                            finalChunkCount != null
                                ? `This document was split into ${finalChunkCount} searchable sections. The AI will reference it when answering relevant questions.`
                                : 'This document is indexed and ready. The AI will use it to answer relevant questionnaire questions.'
                        }
                        style={{ marginBottom: 16 }}
                    />
                )}

                {isFailed && (
                    <Alert
                        type="error"
                        showIcon
                        icon={<ExclamationCircleOutlined />}
                        message="Could not process document"
                        description={
                            <div>
                                <Paragraph style={{ marginBottom: 8 }}>
                                    {doc.errorMessage || liveError || 'An unexpected error occurred while processing this file.'}
                                </Paragraph>
                                <Text type="secondary" style={{ fontSize: 12 }}>
                                    Try converting scanned PDFs to text-based PDF, DOCX, or TXT before uploading.
                                    Old .doc format is not supported — save as .docx first.
                                </Text>
                            </div>
                        }
                        style={{ marginBottom: 16 }}
                    />
                )}

                {/* Business summary row */}
                {isReady && (
                    <Row gutter={12} style={{ marginBottom: 20 }}>
                        <Col span={8}>
                            <Card size="small" style={{ textAlign: 'center' }}>
                                <Statistic
                                    title="Searchable sections"
                                    value={finalChunkCount ?? '—'}
                                    prefix={<DatabaseOutlined />}
                                    valueStyle={{ color: '#52c41a', fontSize: 22 }}
                                />
                                <Text type="secondary" style={{ fontSize: 11 }}>
                                    Each section can independently answer questions
                                </Text>
                            </Card>
                        </Col>
                        <Col span={8}>
                            <Card size="small" style={{ textAlign: 'center' }}>
                                <Statistic
                                    title="Uploaded"
                                    value={dayjs(doc.uploadedAt).format('MMM D, YYYY')}
                                    prefix={<CalendarOutlined />}
                                    valueStyle={{ fontSize: 18 }}
                                />
                            </Card>
                        </Col>
                        <Col span={8}>
                            <Card size="small" style={{ textAlign: 'center' }}>
                                <Statistic
                                    title="Status"
                                    value="Ready for use"
                                    valueStyle={{ color: '#52c41a', fontSize: 16 }}
                                    prefix={<CheckCircleOutlined />}
                                />
                                <Text type="secondary" style={{ fontSize: 11 }}>AI will reference this document</Text>
                            </Card>
                        </Col>
                    </Row>
                )}

                {/*/!* Questions answered using this document *!/*/}
                {/*{isReady && (*/}
                {/*    <Card*/}
                {/*        title={*/}
                {/*            <Space>*/}
                {/*                <QuestionCircleOutlined style={{ color: '#1890ff' }} />*/}
                {/*                <span>Questions answered using this document</span>*/}
                {/*            </Space>*/}
                {/*        }*/}
                {/*        style={{ marginBottom: 16 }}*/}
                {/*        bodyStyle={{ padding: doc.questionsAnswered?.length ? '0 16px' : 16 }}*/}
                {/*    >*/}
                {/*        <QuestionsAnsweredSection doc={doc} />*/}
                {/*    </Card>*/}
                {/*)}*/}

                {/* Document info — compact, secondary */}
                <Card
                    title="Document info"
                    size="small"
                    style={{ marginBottom: 16 }}
                    bodyStyle={{ padding: '12px 16px' }}
                >
                    <Descriptions column={2} size="small" colon>
                        <Descriptions.Item label="File name">{doc.filename}</Descriptions.Item>
                        <Descriptions.Item label="Status">
                            <Tag color={STATUS_COLOR[currentStatus]}>{STATUS_LABELS[currentStatus]}</Tag>
                        </Descriptions.Item>
                        <Descriptions.Item label="Uploaded">
                            {dayjs(doc.uploadedAt).format('MMM D, YYYY HH:mm')}
                        </Descriptions.Item>
                        <Descriptions.Item label="Indexed">
                            {doc.processedAt
                                ? dayjs(doc.processedAt).format('MMM D, YYYY HH:mm')
                                : '—'}
                        </Descriptions.Item>
                    </Descriptions>
                </Card>

                {/* Actions */}
                <div style={{ display: 'flex', gap: 8 }}>
                    <Button onClick={() => navigate('/documents')}>Back to Documents</Button>
                    <Button
                        type="primary"
                        icon={<UploadOutlined />}
                        onClick={() => navigate('/documents/upload')}
                    >
                        Upload another document
                    </Button>
                    {isReady && (
                        <Button
                            icon={<QuestionCircleOutlined />}
                            onClick={() => navigate('/questionnaires/upload')}
                        >
                            Answer a questionnaire
                        </Button>
                    )}
                </div>
            </div>
        </AppLayout>
    );
}