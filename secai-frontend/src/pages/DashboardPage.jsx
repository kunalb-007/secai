// src/pages/DashboardPage.jsx  — REPLACE ENTIRE FILE
import { Typography, Card, Button, Row, Col, Statistic } from 'antd';
import {
    FileTextOutlined, UploadOutlined,
    FileExcelOutlined, PlusOutlined,
} from '@ant-design/icons';
import { Link } from 'react-router-dom';
import AppLayout from '../components/AppLayout';

const { Title, Text } = Typography;

export default function DashboardPage() {
    return (
        <AppLayout>
            <div style={{ padding: 24 }}>
                <Title level={4} style={{ marginBottom: 4 }}>Dashboard</Title>
                <Text type="secondary" style={{ display: 'block', marginBottom: 28 }}>
                    Upload security documents and questionnaires to get started.
                </Text>

                <Row gutter={16}>
                    {/* Security Documents card */}
                    <Col span={12}>
                        <Card
                            title={
                                <span>
                  <FileTextOutlined style={{ marginRight: 8, color: '#1890ff' }} />
                  Security Documents
                </span>
                            }
                            actions={[
                                <Link to="/documents/upload" key="upload">
                                    <UploadOutlined /> Upload Doc
                                </Link>,
                                <Link to="/documents" key="list">View All</Link>,
                            ]}
                        >
                            <Text type="secondary">
                                Upload PDFs, DOCX, or TXT files — your SOC 2 report, security policies,
                                incident response plans, and more. The AI indexes them as a knowledge base.
                            </Text>
                        </Card>
                    </Col>

                    {/* Questionnaires card — now active */}
                    <Col span={12}>
                        <Card
                            title={
                                <span>
                  <FileExcelOutlined style={{ marginRight: 8, color: '#52c41a' }} />
                  Questionnaires
                </span>
                            }
                            actions={[
                                <Link to="/questionnaires/upload" key="upload">
                                    <PlusOutlined /> Upload Questionnaire
                                </Link>,
                                <Link to="/questionnaires" key="list">View All</Link>,
                            ]}
                        >
                            <Text type="secondary">
                                Upload customer security questionnaires (XLSX, CSV, DOCX). Questions are
                                extracted automatically. AI will answer them using your indexed documents.
                            </Text>
                        </Card>
                    </Col>
                </Row>

                {/* Workflow steps */}
                <Card title="How it works" style={{ marginTop: 20 }}>
                    <Row gutter={16}>
                        {[
                            { step: '1', title: 'Upload Documents', desc: 'PDF, DOCX, or TXT security policies' },
                            { step: '2', title: 'Upload Questionnaire', desc: 'XLSX, CSV, or DOCX from customer' },
                            { step: '3', title: 'Generate Answers', desc: 'AI answers using your documents' },
                            { step: '4', title: 'Review & Export', desc: 'Approve edits, export Excel' },
                        ].map((item) => (
                            <Col span={6} key={item.step}>
                                <div style={{ textAlign: 'center', padding: '8px 16px' }}>
                                    <div style={{
                                        width: 36, height: 36, borderRadius: '50%',
                                        background: '#1890ff', color: 'white',
                                        display: 'flex', alignItems: 'center',
                                        justifyContent: 'center', margin: '0 auto 10px',
                                        fontSize: 16, fontWeight: 700,
                                    }}>
                                        {item.step}
                                    </div>
                                    <Text strong style={{ display: 'block' }}>{item.title}</Text>
                                    <Text type="secondary" style={{ fontSize: 12 }}>{item.desc}</Text>
                                </div>
                            </Col>
                        ))}
                    </Row>
                </Card>
            </div>
        </AppLayout>
    );
}